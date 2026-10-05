"""Batched transport for exact Git blob reads and immutable blob-path reads.

No audit result or object content is cached. Git answers every request. Other
commands and unsupported subprocess options use the original subprocess API.
The wrapper enables this only for authenticated, isolated historical audits.
"""
import atexit
import os
from pathlib import Path
import re
import selectors
import subprocess
import tempfile
import threading
import time

ORIGINAL_RUN = subprocess.run
ORIGINAL_POPEN = subprocess.Popen
READER = None
MULTITHREADED = False


def close():
    global READER
    if READER is not None:
        READER.close()
        READER = None


def stamp(path):
    try:
        s = path.stat()
        return (str(path), s.st_dev, s.st_ino, s.st_mtime_ns, s.st_ctime_ns, s.st_size)
    except FileNotFoundError:
        return (str(path), None)


class Reader:
    def __init__(self, executable, cwd, deadline):
        self.owner_pid = os.getpid()
        self.cwd = cwd
        self.executable = executable
        self.process = None
        self.errors = None
        self.selector = None
        remaining = max(0, deadline-time.monotonic())
        query = ORIGINAL_RUN([executable, 'rev-parse', '--absolute-git-dir', '--git-common-dir'],
            cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=remaining)
        if query.returncode or query.stderr:
            raise ValueError('repository identity unavailable')
        entries = query.stdout.decode().splitlines()
        if len(entries) != 2:
            raise ValueError('ambiguous repository identity')
        gitdir, common = (Path(x) if Path(x).is_absolute() else cwd / x for x in entries)
        gitdir, common = gitdir.resolve(), common.resolve()
        # Observe live metadata on every read; no path/owner/config proof is
        # replaced by a cached response from a prior checkout.
        current = cwd
        while not (current / '.git').exists():
            if current.parent == current:
                raise ValueError('missing repository boundary')
            current = current.parent
        self.metadata = [current / '.git', gitdir, common, gitdir / 'commondir',
            common / 'config', gitdir / 'config.worktree', common / 'packed-refs',
            common / 'objects/info/alternates', Path.home() / '.gitconfig',
            Path(os.environ.get('XDG_CONFIG_HOME', str(Path.home()/'.config'))) / 'git/config',
            Path('/etc/gitconfig')]
        self.replacements = common / 'refs/replace'
        if self.replacements.exists():
            raise ValueError('replacement refs require ordinary Git')
        packed = common / 'packed-refs'
        if packed.exists() and b'refs/replace/' in packed.read_bytes():
            raise ValueError('packed replacement refs require ordinary Git')
        # Included configuration can change outside the repository metadata set.
        includes = ORIGINAL_RUN([executable, 'config', '--get-regexp', r'^include'], cwd=cwd,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=max(0, deadline-time.monotonic()))
        if includes.returncode != 1 or includes.stderr:
            raise ValueError('included configuration requires ordinary Git')
        self.identity = tuple(stamp(p) for p in self.metadata)
        self.environment = dict(os.environ)
        try:
            self.errors = tempfile.TemporaryFile()
            self.selector = selectors.DefaultSelector()
            self.process = ORIGINAL_POPEN([executable, '--literal-pathspecs', 'cat-file', '--batch'],
                cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.errors, bufsize=0)
            self.buffer = bytearray()
            self.selector.register(self.process.stdout, selectors.EVENT_READ)
        except BaseException:
            self.close()
            raise

    def valid(self, executable, cwd):
        return (self.owner_pid == os.getpid() and self.process is not None and self.process.poll() is None and
            executable == self.executable and cwd == self.cwd and
            self.environment == dict(os.environ) and not self.replacements.exists() and
            self.identity == tuple(stamp(p) for p in self.metadata))

    def read(self, count, deadline):
        while len(self.buffer) < count:
            remaining = deadline-time.monotonic()
            if remaining <= 0 or not self.selector.select(remaining):
                raise TimeoutError()
            data = os.read(self.process.stdout.fileno(), max(4096, min(65536, count-len(self.buffer))))
            if not data:
                raise ValueError('Git batch ended')
            self.buffer.extend(data)
        data = bytes(self.buffer[:count])
        del self.buffer[:count]
        return data

    def object(self, oid, kind, deadline):
        self.process.stdin.write(oid.encode()+b'\n')
        header = bytearray()
        while not header.endswith(b'\n'):
            header.extend(self.read(1, deadline))
            if len(header) > 128:
                raise ValueError('invalid Git header')
        fields = header.split()
        if (len(fields) != 3 or not re.fullmatch(rb'[0-9a-f]{40}', fields[0]) or
                (re.fullmatch('[0-9a-f]{40}', oid) and fields[0] != oid.encode()) or
                fields[1] != kind or not fields[2].isdigit()):
            raise ValueError('not an exact blob read')
        result = self.read(int(fields[2]), deadline)
        if self.read(1, deadline) != b'\n' or os.fstat(self.errors.fileno()).st_size:
            raise ValueError('Git warning or invalid framing')
        return result

    def blob(self, oid, deadline):
        return self.object(oid, b'blob', deadline)

    def entry(self, revision, path, nul, deadline):
        parent, _, name = path.rpartition('/')
        query = revision + (':' + parent if parent else '^{tree}')
        raw = self.object(query, b'tree', deadline)
        modes = {b'100644': b'100644 blob ', b'100755': b'100755 blob ',
                 b'120000': b'120000 blob ', b'40000': b'040000 tree ',
                 b'160000': b'160000 commit '}
        position = 0
        matches = []
        while position < len(raw):
            space = raw.index(b' ', position)
            end = raw.index(b'\0', space)
            mode, found = raw[position:space], raw[space+1:end]
            if mode not in modes or end+21 > len(raw) or not found or b'/' in found:
                raise ValueError('unsupported Git tree entry')
            oid = raw[end+1:end+21].hex().encode()
            position = end+21
            if found == name.encode('ascii'):
                matches.append(modes[mode] + oid + b'\t' + path.encode('ascii') +
                               (b'\0' if nul else b'\n'))
        if len(matches) > 1:
            raise ValueError('duplicate Git tree entry')
        return matches[0] if matches else b''

    def close(self):
        if self.process is not None:
            if self.owner_pid == os.getpid():
                if self.process.poll() is None:
                    self.process.kill()
                self.process.wait()
            self.process.stdin.close()
            self.process.stdout.close()
        if self.selector is not None:
            self.selector.close()
        if self.errors is not None:
            self.errors.close()


def run(args, *positional, **kwargs):
    global READER, MULTITHREADED
    if threading.active_count() != 1:
        MULTITHREADED = True
    if MULTITHREADED:
        # Do not serialize or change concurrency of a multi-threaded caller.
        # An existing reader belongs solely to its initial thread and is closed
        # at exit; it is never reused after concurrency has been observed.
        return ORIGINAL_RUN(args, *positional, **kwargs)
    allowed = {'cwd', 'stdout', 'stderr', 'timeout', 'check'}
    query = None
    tree_query = None
    if isinstance(args, (list, tuple)) and len(args) >= 3 and args[1] == '--literal-pathspecs':
        if (len(args) == 5 and list(args[2:4]) == ['cat-file', 'blob'] and
                isinstance(args[4], str) and re.fullmatch('[0-9a-f]{40}', args[4])):
            query = args[4]
        elif (len(args) == 4 and args[2] == 'show' and isinstance(args[3], str) and
                re.fullmatch(r'[0-9a-f]{40}:[^\n\r]+', args[3])):
            query = args[3]
        else:
            tail = list(args[3:])
            nul = bool(tail and tail[0] == '-z')
            if nul:
                tail = tail[1:]
            if (args[2] == 'ls-tree' and len(tail) == 3 and tail[1] == '--' and
                    isinstance(tail[0], str) and re.fullmatch('[0-9a-f]{40}', tail[0]) and
                    isinstance(tail[2], str) and re.fullmatch(r'[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*', tail[2]) and
                    all(part not in ('.', '..') for part in tail[2].split('/'))):
                tree_query = (tail[0], tail[2], nul)
    eligible = (not positional and (query is not None or tree_query is not None) and
        isinstance(args[0], str) and args[0].split('/')[-1] == 'git' and
        set(kwargs) <= allowed and
        (kwargs.get('cwd') is None or isinstance(kwargs.get('cwd'), (str, Path))) and
        kwargs.get('cwd') != '' and kwargs.get('stdout') == subprocess.PIPE and
        kwargs.get('stderr') == subprocess.PIPE and
        isinstance(kwargs.get('timeout'), (int, float)) and kwargs['timeout'] > 0 and
        not any(k in os.environ for k in ('GIT_DIR', 'GIT_WORK_TREE', 'GIT_COMMON_DIR',
            'GIT_OBJECT_DIRECTORY', 'GIT_ALTERNATE_OBJECT_DIRECTORIES', 'GIT_REPLACE_REF_BASE',
            'GIT_CONFIG', 'GIT_CONFIG_COUNT', 'GIT_CONFIG_PARAMETERS', 'GIT_CONFIG_GLOBAL', 'GIT_CONFIG_SYSTEM')))
    if not eligible:
        # Tree enumeration often alternates with exact blob reads. These
        # Git queries cannot modify objects/configuration; still execute each
        # query normally and revalidate all reader metadata on the next read.
        readonly = (not positional and isinstance(args, (list, tuple)) and len(args) >= 3 and
            isinstance(args[0], str) and args[0].split('/')[-1] == 'git' and
            args[1] == '--literal-pathspecs' and
            (args[2] in ('ls-tree', 'rev-parse') or
             (len(args) == 4 and args[2] == 'show' and isinstance(args[3], str) and
              re.fullmatch(r'[0-9a-f]{40}:[^\n\r]+', args[3]))) and
            not kwargs.get('shell', False))
        if not readonly:
            close()
        return ORIGINAL_RUN(args, *positional, **kwargs)
    deadline = time.monotonic()+kwargs['timeout']
    try:
        cwd = Path(kwargs.get('cwd') if kwargs.get('cwd') is not None else os.getcwd()).resolve()
        if READER is None or not READER.valid(args[0], cwd):
            close()
            READER = Reader(args[0], cwd, deadline)
        result = (READER.entry(*tree_query, deadline) if tree_query is not None else
                  READER.blob(query, deadline))
        return subprocess.CompletedProcess(args, 0, result, b'')
    except (TimeoutError, subprocess.TimeoutExpired):
        close()
        raise subprocess.TimeoutExpired(args, kwargs['timeout'])
    except (OSError, ValueError):
        close()
        remaining = deadline-time.monotonic()
        if remaining <= 0:
            raise subprocess.TimeoutExpired(args, kwargs['timeout'])
        return ORIGINAL_RUN(args, **dict(kwargs, timeout=remaining))

def install():
    subprocess.run = run
    atexit.register(close)
