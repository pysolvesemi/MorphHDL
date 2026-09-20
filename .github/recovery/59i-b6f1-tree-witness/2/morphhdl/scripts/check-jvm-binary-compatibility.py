#!/usr/bin/env python3
"""Check JVM binary compatibility between baseline and current artifacts.

The checker intentionally has no third-party dependencies.  It reads class
files directly from one or more class directories or JAR files and treats the
baseline public/protected surface as the contract.  Additive concrete API is
allowed, but a newly added public/protected abstract method on a pre-existing
class or interface is rejected because it can break already compiled
implementations.

Diagnostics use stable ``JVMABI_*`` codes so callers can distinguish failures
without parsing explanatory prose.  Run ``--self-test`` for isolated parser
and compatibility-policy regression tests; the fixtures are generated as
class-file bytes and require neither javac nor sbt.
"""

from __future__ import annotations

import argparse
import json
import re
import struct
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, Iterator, List, Mapping, Optional, Sequence, Tuple


ACC_PUBLIC = 0x0001
ACC_PRIVATE = 0x0002
ACC_PROTECTED = 0x0004
ACC_STATIC = 0x0008
ACC_FINAL = 0x0010
ACC_BRIDGE = 0x0040
ACC_INTERFACE = 0x0200
ACC_ABSTRACT = 0x0400
ACC_SYNTHETIC = 0x1000
ACC_ANNOTATION = 0x2000
ACC_ENUM = 0x4000
ACC_MODULE = 0x8000

VISIBILITY_MASK = ACC_PUBLIC | ACC_PRIVATE | ACC_PROTECTED

SCALA_REFLECTIVE_CALL_CACHE_NAME = re.compile(r"reflMethod\$Method[0-9]+\Z")
SCALA_REFLECTIVE_CALL_CACHE_DESCRIPTOR = (
    "(Ljava/lang/Class;)Ljava/lang/reflect/Method;"
)


class ClassFormatError(ValueError):
    """Raised when an input is not a supported, well-formed class artifact."""


@dataclass(frozen=True)
class MemberInfo:
    name: str
    descriptor: str
    access_flags: int


@dataclass(frozen=True)
class ClassInfo:
    name: str
    super_name: Optional[str]
    interfaces: Tuple[str, ...]
    access_flags: int
    fields: Tuple[MemberInfo, ...]
    methods: Tuple[MemberInfo, ...]
    inner_access_flags: Optional[int] = None
    outer_name: Optional[str] = None

    @property
    def visibility_flags(self) -> int:
        if self.inner_access_flags is not None:
            return self.inner_access_flags
        return self.access_flags

    @property
    def is_nested_static(self) -> bool:
        return bool(
            self.inner_access_flags is not None
            and self.inner_access_flags & ACC_STATIC
        )


@dataclass(frozen=True)
class Diagnostic:
    code: str
    owner: str
    member: Optional[str] = None
    baseline: Optional[str] = None
    current: Optional[str] = None
    detail: Optional[str] = None

    def sort_key(self) -> Tuple[str, str, str, str, str, str]:
        return (
            self.owner,
            self.member or "",
            self.baseline or "",
            self.current or "",
            self.code,
            self.detail or "",
        )

    def render(self, label: Optional[str] = None) -> str:
        values = []
        if label:
            values.append(("artifact", label))
        values.append(("owner", self.owner))
        if self.member is not None:
            values.append(("member", self.member))
        if self.baseline is not None:
            values.append(("baseline", self.baseline))
        if self.current is not None:
            values.append(("current", self.current))
        if self.detail is not None:
            values.append(("detail", self.detail))
        rendered = " ".join(
            f"{key}={json.dumps(value, ensure_ascii=True)}" for key, value in values
        )
        return f"{self.code}: {rendered}"


class _Reader:
    def __init__(self, data: bytes, source: str):
        self._data = memoryview(data)
        self._offset = 0
        self.source = source

    @property
    def remaining(self) -> int:
        return len(self._data) - self._offset

    def take(self, size: int) -> bytes:
        if size < 0 or self._offset + size > len(self._data):
            raise ClassFormatError(
                f"{self.source}: truncated class file at byte {self._offset}"
            )
        start = self._offset
        self._offset += size
        return self._data[start : start + size].tobytes()

    def u1(self) -> int:
        return self.take(1)[0]

    def u2(self) -> int:
        return struct.unpack(">H", self.take(2))[0]

    def u4(self) -> int:
        return struct.unpack(">I", self.take(4))[0]


def _modified_utf8(raw: bytes, source: str) -> str:
    # JVM modified UTF-8 represents NUL as C0 80 and supplementary characters
    # as UTF-16 surrogate pairs.  Names/descriptors are normally ASCII, but
    # accepting both forms keeps the parser correct for Unicode identifiers.
    raw = raw.replace(b"\xc0\x80", b"\x00")
    try:
        return raw.decode("utf-8", errors="surrogatepass")
    except UnicodeDecodeError as error:
        raise ClassFormatError(f"{source}: invalid modified UTF-8 constant") from error


def parse_class(data: bytes, source: str = "<memory>") -> ClassInfo:
    """Parse the compatibility-relevant portion of one JVM class file."""

    reader = _Reader(data, source)
    if reader.u4() != 0xCAFEBABE:
        raise ClassFormatError(f"{source}: invalid class-file magic")
    reader.u2()  # minor_version
    reader.u2()  # major_version

    constant_pool_count = reader.u2()
    if constant_pool_count == 0:
        raise ClassFormatError(f"{source}: invalid zero-sized constant pool")
    constant_pool: List[Optional[Tuple[int, object]]] = [None] * constant_pool_count
    index = 1
    while index < constant_pool_count:
        tag = reader.u1()
        if tag == 1:  # CONSTANT_Utf8
            length = reader.u2()
            constant_pool[index] = (tag, _modified_utf8(reader.take(length), source))
        elif tag in (3, 4):  # Integer, Float
            reader.take(4)
            constant_pool[index] = (tag, None)
        elif tag in (5, 6):  # Long, Double; each occupies two CP slots
            reader.take(8)
            constant_pool[index] = (tag, None)
            index += 1
            if index >= constant_pool_count:
                raise ClassFormatError(f"{source}: invalid wide constant-pool entry")
        elif tag in (7, 8, 16, 19, 20):
            constant_pool[index] = (tag, reader.u2())
        elif tag in (9, 10, 11, 12, 17, 18):
            constant_pool[index] = (tag, (reader.u2(), reader.u2()))
        elif tag == 15:
            constant_pool[index] = (tag, (reader.u1(), reader.u2()))
        else:
            raise ClassFormatError(
                f"{source}: unsupported constant-pool tag {tag} at index {index}"
            )
        index += 1

    def cp_entry(cp_index: int, expected_tag: int) -> object:
        if cp_index <= 0 or cp_index >= len(constant_pool):
            raise ClassFormatError(
                f"{source}: constant-pool index {cp_index} is out of range"
            )
        entry = constant_pool[cp_index]
        if entry is None or entry[0] != expected_tag:
            actual = "empty" if entry is None else str(entry[0])
            raise ClassFormatError(
                f"{source}: constant-pool index {cp_index} has tag {actual}, "
                f"expected {expected_tag}"
            )
        return entry[1]

    def cp_utf8(cp_index: int) -> str:
        value = cp_entry(cp_index, 1)
        assert isinstance(value, str)
        return value

    def cp_class(cp_index: int) -> str:
        name_index = cp_entry(cp_index, 7)
        assert isinstance(name_index, int)
        return cp_utf8(name_index)

    access_flags = reader.u2()
    this_class_index = reader.u2()
    super_class_index = reader.u2()
    class_name = cp_class(this_class_index)
    super_name = cp_class(super_class_index) if super_class_index else None

    interfaces = tuple(cp_class(reader.u2()) for _ in range(reader.u2()))

    def parse_members() -> Tuple[MemberInfo, ...]:
        members: List[MemberInfo] = []
        seen = set()
        for _ in range(reader.u2()):
            member_access = reader.u2()
            member_name = cp_utf8(reader.u2())
            descriptor = cp_utf8(reader.u2())
            key = (member_name, descriptor)
            if key in seen:
                raise ClassFormatError(
                    f"{source}: duplicate member {member_name}{descriptor}"
                )
            seen.add(key)
            for _ in range(reader.u2()):
                cp_utf8(reader.u2())
                reader.take(reader.u4())
            members.append(MemberInfo(member_name, descriptor, member_access))
        return tuple(members)

    fields = parse_members()
    methods = parse_members()

    inner_access_flags: Optional[int] = None
    outer_name: Optional[str] = None
    for _ in range(reader.u2()):
        attribute_name = cp_utf8(reader.u2())
        payload = reader.take(reader.u4())
        if attribute_name != "InnerClasses":
            continue
        inner_reader = _Reader(payload, f"{source}:InnerClasses")
        for _ in range(inner_reader.u2()):
            inner_class_index = inner_reader.u2()
            outer_class_index = inner_reader.u2()
            inner_reader.u2()  # inner_name_index; zero for anonymous classes
            candidate_flags = inner_reader.u2()
            if inner_class_index and cp_class(inner_class_index) == class_name:
                candidate_outer = cp_class(outer_class_index) if outer_class_index else None
                if inner_access_flags is not None and (
                    inner_access_flags != candidate_flags or outer_name != candidate_outer
                ):
                    raise ClassFormatError(
                        f"{source}: conflicting self entries in InnerClasses"
                    )
                inner_access_flags = candidate_flags
                outer_name = candidate_outer
        if inner_reader.remaining:
            raise ClassFormatError(
                f"{source}: trailing bytes in InnerClasses attribute"
            )

    if reader.remaining:
        raise ClassFormatError(f"{source}: trailing bytes after class definition")

    return ClassInfo(
        name=class_name,
        super_name=super_name,
        interfaces=interfaces,
        access_flags=access_flags,
        fields=fields,
        methods=methods,
        inner_access_flags=inner_access_flags,
        outer_name=outer_name,
    )


def _class_entries(path: Path) -> Iterator[Tuple[str, bytes]]:
    if path.is_dir():
        for class_file in sorted(path.rglob("*.class")):
            relative = class_file.relative_to(path).as_posix()
            yield f"{path}:{relative}", class_file.read_bytes()
        return

    if path.is_file() and path.suffix.lower() in (".jar", ".zip"):
        try:
            with zipfile.ZipFile(path) as archive:
                entries = sorted(
                    (
                        info
                        for info in archive.infolist()
                        if not info.is_dir()
                        and info.filename.endswith(".class")
                        and not info.filename.startswith("META-INF/versions/")
                    ),
                    key=lambda info: info.filename,
                )
                for entry in entries:
                    yield f"{path}!/{entry.filename}", archive.read(entry)
        except (OSError, zipfile.BadZipFile) as error:
            raise ClassFormatError(f"{path}: cannot read JAR/ZIP: {error}") from error
        return

    if not path.exists():
        raise ClassFormatError(f"input does not exist: {path}")
    raise ClassFormatError(
        f"input must be a class directory or JAR/ZIP file: {path}"
    )


def load_classes(paths: Sequence[Path]) -> Dict[str, ClassInfo]:
    """Load a deterministic class-name map from directories and/or JARs."""

    if not paths:
        raise ClassFormatError("at least one class directory or JAR is required")
    classes: Dict[str, ClassInfo] = {}
    origins: Dict[str, str] = {}
    for path in paths:
        for source, data in _class_entries(path):
            parsed = parse_class(data, source)
            previous = classes.get(parsed.name)
            if previous is not None and previous != parsed:
                raise ClassFormatError(
                    f"duplicate class {parsed.name} differs between "
                    f"{origins[parsed.name]} and {source}"
                )
            classes[parsed.name] = parsed
            origins[parsed.name] = source
    return classes


def _visibility_level(flags: int) -> int:
    if flags & ACC_PUBLIC:
        return 3
    if flags & ACC_PROTECTED:
        return 2
    if flags & ACC_PRIVATE:
        return 0
    return 1  # package-private


def _visibility_name(flags: int) -> str:
    if flags & ACC_PUBLIC:
        return "public"
    if flags & ACC_PROTECTED:
        return "protected"
    if flags & ACC_PRIVATE:
        return "private"
    return "package-private"


def _is_scala_reflective_call_cache(member: MemberInfo) -> bool:
    """Recognize Scala's owner-local structural-call reflection lookup thunk."""

    return (
        member.access_flags == (ACC_PUBLIC | ACC_STATIC)
        and member.descriptor == SCALA_REFLECTIVE_CALL_CACHE_DESCRIPTOR
        and SCALA_REFLECTIVE_CALL_CACHE_NAME.fullmatch(member.name) is not None
    )


def _is_api_member(member: MemberInfo) -> bool:
    if not member.access_flags & (ACC_PUBLIC | ACC_PROTECTED):
        return False
    # Scala emits public implementation bodies which routinely renumber after
    # unrelated body edits.  Structural-call reflection lookup thunks are not
    # marked ACC_SYNTHETIC in Scala 2.12 classfiles, so recognize only their
    # exact compiler shape.  Other synthetic members remain contractual: Scala
    # trait static helpers and named-inner-class $outer accessors are referenced
    # by compiled bytecode, while erased callers can link to bridge methods.
    if _is_scala_reflective_call_cache(member):
        return False
    if (
        member.access_flags & ACC_SYNTHETIC
        and not member.access_flags & ACC_BRIDGE
        and (
            member.name.startswith("$anonfun$")
            or member.name.startswith("lambda$")
        )
    ):
        return False
    return True


def _is_api_class(
    name: str,
    classes: Mapping[str, ClassInfo],
    visiting: Optional[set] = None,
) -> bool:
    info = classes[name]
    if info.access_flags & (ACC_MODULE | ACC_SYNTHETIC):
        return False
    if not info.visibility_flags & (ACC_PUBLIC | ACC_PROTECTED):
        return False
    if info.inner_access_flags is None:
        return True
    # A self entry with no enclosing class denotes an anonymous or local
    # implementation class.  Scala commonly gives these classes public JVM
    # flags even though no source-level client can name them.
    if info.outer_name is None:
        return False
    outer = classes.get(info.outer_name)
    if outer is None:
        # The enclosing class may be supplied by another artifact.  Retaining
        # the nested class is the conservative compatibility requirement.
        return True
    if visiting is None:
        visiting = set()
    if name in visiting:
        raise ClassFormatError(f"cyclic InnerClasses ownership involving {name}")
    visiting = set(visiting)
    visiting.add(name)
    return _is_api_class(outer.name, classes, visiting)


def _class_kind(flags: int) -> str:
    if flags & ACC_ANNOTATION:
        return "annotation"
    if flags & ACC_INTERFACE:
        return "interface"
    if flags & ACC_ENUM:
        return "enum"
    return "class"


def _member_signature(member: MemberInfo) -> str:
    return f"{member.name}{member.descriptor}"


def _alternative_descriptors(members: Iterable[MemberInfo], name: str) -> str:
    descriptors = sorted({member.descriptor for member in members if member.name == name})
    return ",".join(descriptors)


def _compare_field(
    owner: str,
    baseline: MemberInfo,
    current: ClassInfo,
) -> List[Diagnostic]:
    diagnostics: List[Diagnostic] = []
    current_by_key = {(field.name, field.descriptor): field for field in current.fields}
    candidate = current_by_key.get((baseline.name, baseline.descriptor))
    member_name = _member_signature(baseline)
    if candidate is None:
        alternatives = _alternative_descriptors(current.fields, baseline.name)
        if alternatives:
            diagnostics.append(
                Diagnostic(
                    "JVMABI_FIELD_DESCRIPTOR_CHANGED",
                    owner,
                    member_name,
                    baseline.descriptor,
                    alternatives,
                    "baseline field descriptor is absent",
                )
            )
        else:
            diagnostics.append(
                Diagnostic(
                    "JVMABI_MISSING_FIELD",
                    owner,
                    member_name,
                    baseline.descriptor,
                    None,
                    "baseline field is absent",
                )
            )
        return diagnostics

    if _visibility_level(candidate.access_flags) < _visibility_level(
        baseline.access_flags
    ):
        diagnostics.append(
            Diagnostic(
                "JVMABI_FIELD_VISIBILITY_REDUCED",
                owner,
                member_name,
                _visibility_name(baseline.access_flags),
                _visibility_name(candidate.access_flags),
            )
        )
    if bool(candidate.access_flags & ACC_STATIC) != bool(
        baseline.access_flags & ACC_STATIC
    ):
        diagnostics.append(
            Diagnostic(
                "JVMABI_FIELD_STATIC_CHANGED",
                owner,
                member_name,
                "static" if baseline.access_flags & ACC_STATIC else "instance",
                "static" if candidate.access_flags & ACC_STATIC else "instance",
            )
        )
    if not baseline.access_flags & ACC_FINAL and candidate.access_flags & ACC_FINAL:
        diagnostics.append(
            Diagnostic(
                "JVMABI_FIELD_BECAME_FINAL",
                owner,
                member_name,
                "non-final",
                "final",
            )
        )
    return diagnostics


def _compare_method(
    owner: str,
    baseline: MemberInfo,
    current: ClassInfo,
) -> List[Diagnostic]:
    diagnostics: List[Diagnostic] = []
    current_by_key = {(method.name, method.descriptor): method for method in current.methods}
    candidate = current_by_key.get((baseline.name, baseline.descriptor))
    is_constructor = baseline.name == "<init>"
    noun = "CONSTRUCTOR" if is_constructor else "METHOD"
    member_name = _member_signature(baseline)
    if candidate is None:
        alternatives = _alternative_descriptors(current.methods, baseline.name)
        if alternatives:
            diagnostics.append(
                Diagnostic(
                    f"JVMABI_{noun}_DESCRIPTOR_CHANGED",
                    owner,
                    member_name,
                    baseline.descriptor,
                    alternatives,
                    "baseline descriptor is absent",
                )
            )
        else:
            diagnostics.append(
                Diagnostic(
                    f"JVMABI_MISSING_{noun}",
                    owner,
                    member_name,
                    baseline.descriptor,
                    None,
                    "baseline callable is absent",
                )
            )
        return diagnostics

    if _visibility_level(candidate.access_flags) < _visibility_level(
        baseline.access_flags
    ):
        diagnostics.append(
            Diagnostic(
                f"JVMABI_{noun}_VISIBILITY_REDUCED",
                owner,
                member_name,
                _visibility_name(baseline.access_flags),
                _visibility_name(candidate.access_flags),
            )
        )
    if not is_constructor and bool(candidate.access_flags & ACC_STATIC) != bool(
        baseline.access_flags & ACC_STATIC
    ):
        diagnostics.append(
            Diagnostic(
                "JVMABI_METHOD_STATIC_CHANGED",
                owner,
                member_name,
                "static" if baseline.access_flags & ACC_STATIC else "instance",
                "static" if candidate.access_flags & ACC_STATIC else "instance",
            )
        )
    if not is_constructor and not baseline.access_flags & ACC_FINAL and (
        candidate.access_flags & ACC_FINAL
    ):
        diagnostics.append(
            Diagnostic(
                "JVMABI_METHOD_BECAME_FINAL",
                owner,
                member_name,
                "non-final",
                "final",
            )
        )
    if not is_constructor and not baseline.access_flags & ACC_ABSTRACT and (
        candidate.access_flags & ACC_ABSTRACT
    ):
        diagnostics.append(
            Diagnostic(
                "JVMABI_METHOD_BECAME_ABSTRACT",
                owner,
                member_name,
                "concrete",
                "abstract",
            )
        )
    return diagnostics


@dataclass(frozen=True)
class _EffectiveMethod:
    owner: str
    member: MemberInfo


class _EffectiveMethodResolver:
    """Resolve inherited instance methods relevant to abstract obligations."""

    _OBJECT_CONCRETE_METHODS = {
        ("equals", "(Ljava/lang/Object;)Z"),
        ("hashCode", "()I"),
        ("toString", "()Ljava/lang/String;"),
    }

    def __init__(self, classes: Mapping[str, ClassInfo]):
        self.classes = classes
        self._interface_cache: Dict[
            str, Dict[Tuple[str, str], _EffectiveMethod]
        ] = {}
        self._subtype_cache: Dict[Tuple[str, str], bool] = {}

    @staticmethod
    def _is_instance_api_method(method: MemberInfo) -> bool:
        return (
            method.name not in ("<init>", "<clinit>")
            and _is_api_member(method)
            and not method.access_flags & ACC_STATIC
        )

    def _interface_extends(
        self,
        child: str,
        ancestor: str,
        visiting: Optional[set] = None,
    ) -> bool:
        if child == ancestor:
            return True
        key = (child, ancestor)
        cached = self._subtype_cache.get(key)
        if cached is not None:
            return cached
        info = self.classes.get(child)
        if info is None or not info.access_flags & ACC_INTERFACE:
            self._subtype_cache[key] = False
            return False
        if visiting is None:
            visiting = set()
        if child in visiting:
            raise ClassFormatError(
                f"cyclic interface hierarchy involving {child}"
            )
        visiting = set(visiting)
        visiting.add(child)
        result = any(
            self._interface_extends(parent, ancestor, visiting)
            for parent in info.interfaces
        )
        self._subtype_cache[key] = result
        return result

    def _maximal_interfaces(self, names: Iterable[str]) -> Tuple[str, ...]:
        candidates = sorted(set(names))
        return tuple(
            candidate
            for candidate in candidates
            if not any(
                other != candidate
                and self._interface_extends(other, candidate)
                for other in candidates
            )
        )

    @staticmethod
    def _merge_interface_method(
        methods: Dict[Tuple[str, str], _EffectiveMethod],
        key: Tuple[str, str],
        candidate: _EffectiveMethod,
    ) -> None:
        previous = methods.get(key)
        if previous is None or (
            previous.member.access_flags & ACC_ABSTRACT
            and not candidate.member.access_flags & ACC_ABSTRACT
        ):
            # A concrete/default method satisfies an unrelated abstract parent
            # declaration.  More-specific parents are selected before merging.
            methods[key] = candidate

    def _interface_methods(
        self,
        name: str,
        visiting: Optional[set] = None,
    ) -> Dict[Tuple[str, str], _EffectiveMethod]:
        cached = self._interface_cache.get(name)
        if cached is not None:
            return dict(cached)
        info = self.classes.get(name)
        if info is None or not info.access_flags & ACC_INTERFACE:
            return {}
        if visiting is None:
            visiting = set()
        if name in visiting:
            raise ClassFormatError(f"cyclic interface hierarchy involving {name}")
        visiting = set(visiting)
        visiting.add(name)

        methods: Dict[Tuple[str, str], _EffectiveMethod] = {}
        for parent in self._maximal_interfaces(info.interfaces):
            for key, method in self._interface_methods(parent, visiting).items():
                self._merge_interface_method(methods, key, method)
        for method in info.methods:
            if self._is_instance_api_method(method):
                methods[(method.name, method.descriptor)] = _EffectiveMethod(
                    name, method
                )
        self._interface_cache[name] = dict(methods)
        return methods

    def _class_methods(self, name: str) -> Dict[Tuple[str, str], _EffectiveMethod]:
        chain: List[Tuple[str, ClassInfo]] = []
        visiting = set()
        cursor: Optional[str] = name
        while cursor is not None:
            if cursor in visiting:
                raise ClassFormatError(f"cyclic superclass hierarchy involving {cursor}")
            visiting.add(cursor)
            info = self.classes.get(cursor)
            if info is None or info.access_flags & ACC_INTERFACE:
                break
            chain.append((cursor, info))
            cursor = info.super_name

        methods: Dict[Tuple[str, str], _EffectiveMethod] = {}
        interface_names = (
            interface
            for _, info in chain
            for interface in info.interfaces
        )
        for interface in self._maximal_interfaces(interface_names):
            for key, method in self._interface_methods(interface).items():
                self._merge_interface_method(methods, key, method)

        # Class declarations take precedence over interface defaults, and the
        # most-derived class declaration takes precedence over its superclass.
        for owner, info in reversed(chain):
            for method in info.methods:
                if self._is_instance_api_method(method):
                    methods[(method.name, method.descriptor)] = _EffectiveMethod(
                        owner, method
                    )
        return methods

    def abstract_requirements(
        self, name: str
    ) -> Dict[Tuple[str, str], _EffectiveMethod]:
        info = self.classes[name]
        if info.access_flags & ACC_INTERFACE:
            methods = self._interface_methods(name)
        else:
            # A compiler-produced concrete class has no outstanding abstract
            # obligations; this also avoids guessing about methods supplied by
            # a superclass in another artifact.
            if not info.access_flags & ACC_ABSTRACT:
                return {}
            methods = self._class_methods(name)
        return {
            key: method
            for key, method in methods.items()
            if key not in self._OBJECT_CONCRETE_METHODS
            and method.member.access_flags & ACC_ABSTRACT
        }


def compare_classes(
    baseline_classes: Mapping[str, ClassInfo],
    current_classes: Mapping[str, ClassInfo],
) -> List[Diagnostic]:
    """Return all compatibility failures in deterministic order."""

    diagnostics: List[Diagnostic] = []
    baseline_method_resolver = _EffectiveMethodResolver(baseline_classes)
    current_method_resolver = _EffectiveMethodResolver(current_classes)
    baseline_api_names = sorted(
        name for name in baseline_classes if _is_api_class(name, baseline_classes)
    )

    for name in baseline_api_names:
        baseline = baseline_classes[name]
        current = current_classes.get(name)
        if current is None:
            diagnostics.append(
                Diagnostic(
                    "JVMABI_MISSING_CLASS",
                    name,
                    baseline=_class_kind(baseline.access_flags),
                    detail="baseline API class is absent",
                )
            )
            continue

        if _visibility_level(current.visibility_flags) < _visibility_level(
            baseline.visibility_flags
        ):
            diagnostics.append(
                Diagnostic(
                    "JVMABI_CLASS_VISIBILITY_REDUCED",
                    name,
                    baseline=_visibility_name(baseline.visibility_flags),
                    current=_visibility_name(current.visibility_flags),
                )
            )

        baseline_kind = _class_kind(baseline.access_flags)
        current_kind = _class_kind(current.access_flags)
        if current_kind != baseline_kind:
            diagnostics.append(
                Diagnostic(
                    "JVMABI_CLASS_KIND_CHANGED",
                    name,
                    baseline=baseline_kind,
                    current=current_kind,
                )
            )

        if baseline.is_nested_static != current.is_nested_static:
            diagnostics.append(
                Diagnostic(
                    "JVMABI_CLASS_STATIC_CHANGED",
                    name,
                    baseline="static" if baseline.is_nested_static else "inner",
                    current="static" if current.is_nested_static else "inner",
                )
            )

        if not baseline.access_flags & ACC_FINAL and current.access_flags & ACC_FINAL:
            diagnostics.append(
                Diagnostic(
                    "JVMABI_CLASS_BECAME_FINAL",
                    name,
                    baseline="non-final",
                    current="final",
                )
            )

        if (
            baseline_kind == "class"
            and not baseline.access_flags & ACC_ABSTRACT
            and current.access_flags & ACC_ABSTRACT
        ):
            diagnostics.append(
                Diagnostic(
                    "JVMABI_CLASS_BECAME_ABSTRACT",
                    name,
                    baseline="concrete",
                    current="abstract",
                )
            )

        if current.super_name != baseline.super_name:
            diagnostics.append(
                Diagnostic(
                    "JVMABI_SUPERCLASS_CHANGED",
                    name,
                    baseline=baseline.super_name or "<none>",
                    current=current.super_name or "<none>",
                )
            )

        current_interfaces = set(current.interfaces)
        for missing_interface in sorted(set(baseline.interfaces) - current_interfaces):
            diagnostics.append(
                Diagnostic(
                    "JVMABI_INTERFACE_REMOVED",
                    name,
                    baseline=missing_interface,
                    current=",".join(sorted(current_interfaces)) or "<none>",
                )
            )

        for field in baseline.fields:
            if _is_api_member(field):
                diagnostics.extend(_compare_field(name, field, current))

        baseline_api_method_keys = {
            (method.name, method.descriptor)
            for method in baseline.methods
            if _is_api_member(method)
        }
        for method in baseline.methods:
            if _is_api_member(method) and method.name != "<clinit>":
                diagnostics.extend(_compare_method(name, method, current))

        baseline_requirements = baseline_method_resolver.abstract_requirements(name)
        current_requirements = current_method_resolver.abstract_requirements(name)
        for key in sorted(set(current_requirements) - set(baseline_requirements)):
            # A directly declared baseline method is already diagnosed as
            # missing or as becoming abstract by the member comparison above.
            if key in baseline_api_method_keys:
                continue
            requirement = current_requirements[key]
            origin = (
                "declared on this type"
                if requirement.owner == name
                else f"inherited from {requirement.owner}"
            )
            diagnostics.append(
                Diagnostic(
                    "JVMABI_ADDED_ABSTRACT_METHOD",
                    name,
                    _member_signature(requirement.member),
                    baseline="<absent>",
                    current="abstract",
                    detail=(
                        f"new abstract API requirement {origin} can break "
                        "compiled implementations"
                    ),
                )
            )

    return sorted(diagnostics, key=Diagnostic.sort_key)


def _u1(value: int) -> bytes:
    return struct.pack(">B", value)


def _u2(value: int) -> bytes:
    return struct.pack(">H", value)


def _u4(value: int) -> bytes:
    return struct.pack(">I", value)


def _fixture_class(
    name: str,
    *,
    access: int = ACC_PUBLIC,
    super_name: Optional[str] = "java/lang/Object",
    interfaces: Sequence[str] = (),
    fields: Sequence[Tuple[str, str, int]] = (),
    methods: Sequence[Tuple[str, str, int]] = (),
    inner_access: Optional[int] = None,
    outer_name: Optional[str] = None,
) -> bytes:
    """Create a minimal structural class-file fixture for isolated self-tests."""

    entries: List[Tuple[int, object]] = []
    utf8_indexes: Dict[str, int] = {}
    class_indexes: Dict[str, int] = {}

    def utf8(value: str) -> int:
        previous = utf8_indexes.get(value)
        if previous is not None:
            return previous
        encoded = value.encode("utf-8")
        entries.append((1, encoded))
        result = len(entries)
        utf8_indexes[value] = result
        return result

    def class_index(value: str) -> int:
        previous = class_indexes.get(value)
        if previous is not None:
            return previous
        name_index = utf8(value)
        entries.append((7, name_index))
        result = len(entries)
        class_indexes[value] = result
        return result

    this_index = class_index(name)
    super_index = class_index(super_name) if super_name else 0
    interface_indexes = [class_index(interface) for interface in interfaces]
    field_indexes = [
        (utf8(field_name), utf8(descriptor), flags)
        for field_name, descriptor, flags in fields
    ]
    method_indexes = [
        (utf8(method_name), utf8(descriptor), flags)
        for method_name, descriptor, flags in methods
    ]

    inner_attribute_name = 0
    outer_index = 0
    inner_name_index = 0
    if inner_access is not None:
        inner_attribute_name = utf8("InnerClasses")
        outer_index = class_index(outer_name) if outer_name else 0
        simple_name = name.rsplit("$", 1)[-1]
        inner_name_index = utf8(simple_name)

    constant_pool = bytearray()
    for tag, payload in entries:
        constant_pool += _u1(tag)
        if tag == 1:
            assert isinstance(payload, bytes)
            constant_pool += _u2(len(payload)) + payload
        elif tag == 7:
            assert isinstance(payload, int)
            constant_pool += _u2(payload)
        else:  # pragma: no cover - fixture builder only creates Utf8/Class
            raise AssertionError(f"unexpected fixture constant-pool tag {tag}")

    result = bytearray()
    result += _u4(0xCAFEBABE) + _u2(0) + _u2(52)
    result += _u2(len(entries) + 1) + constant_pool
    result += _u2(access) + _u2(this_index) + _u2(super_index)
    result += _u2(len(interface_indexes))
    for interface_index in interface_indexes:
        result += _u2(interface_index)

    result += _u2(len(field_indexes))
    for name_index, descriptor_index, flags in field_indexes:
        result += _u2(flags) + _u2(name_index) + _u2(descriptor_index) + _u2(0)

    result += _u2(len(method_indexes))
    for name_index, descriptor_index, flags in method_indexes:
        result += _u2(flags) + _u2(name_index) + _u2(descriptor_index) + _u2(0)

    if inner_access is None:
        result += _u2(0)
    else:
        payload = (
            _u2(1)
            + _u2(this_index)
            + _u2(outer_index)
            + _u2(inner_name_index)
            + _u2(inner_access)
        )
        result += _u2(1) + _u2(inner_attribute_name) + _u4(len(payload)) + payload
    return bytes(result)


def _write_fixture_directory(path: Path, classes: Mapping[str, bytes]) -> None:
    for name, data in sorted(classes.items()):
        class_path = path / f"{name}.class"
        class_path.parent.mkdir(parents=True, exist_ok=True)
        class_path.write_bytes(data)


def _write_fixture_jar(path: Path, classes: Mapping[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, data in sorted(classes.items()):
            archive.writestr(f"{name}.class", data)


def run_self_tests() -> int:
    cases = 0

    def check(
        case_name: str,
        baseline: Mapping[str, bytes],
        current: Mapping[str, bytes],
        expected_codes: Sequence[str],
        *,
        use_jars: bool = False,
    ) -> None:
        nonlocal cases
        cases += 1
        with tempfile.TemporaryDirectory(prefix="morphhdl-jvmabi-self-test-") as temp:
            root = Path(temp)
            if use_jars:
                baseline_path = root / "baseline.jar"
                current_path = root / "current.jar"
                _write_fixture_jar(baseline_path, baseline)
                _write_fixture_jar(current_path, current)
            else:
                baseline_path = root / "baseline"
                current_path = root / "current"
                baseline_path.mkdir()
                current_path.mkdir()
                _write_fixture_directory(baseline_path, baseline)
                _write_fixture_directory(current_path, current)
            actual = compare_classes(
                load_classes([baseline_path]), load_classes([current_path])
            )
        actual_codes = sorted(diagnostic.code for diagnostic in actual)
        wanted_codes = sorted(expected_codes)
        if actual_codes != wanted_codes:
            rendered = "\n".join(diagnostic.render(case_name) for diagnostic in actual)
            raise AssertionError(
                f"{case_name}: expected {wanted_codes}, got {actual_codes}\n{rendered}"
            )

    constructor = ("<init>", "()V", ACC_PUBLIC)
    base = {
        "api/Sample": _fixture_class(
            "api/Sample",
            fields=(("value", "I", ACC_PUBLIC),),
            methods=(constructor, ("read", "()I", ACC_PROTECTED)),
        )
    }
    additive = {
        "api/Sample": _fixture_class(
            "api/Sample",
            fields=(
                ("value", "I", ACC_PUBLIC),
                ("extra", "J", ACC_PUBLIC | ACC_STATIC),
            ),
            methods=(
                constructor,
                ("read", "()I", ACC_PUBLIC),
                ("extra", "(I)I", ACC_PUBLIC | ACC_STATIC),
            ),
            interfaces=("api/NewMarker",),
        ),
        "api/NewClass": _fixture_class(
            "api/NewClass",
            access=ACC_PUBLIC | ACC_ABSTRACT,
            methods=(("required", "()V", ACC_PUBLIC | ACC_ABSTRACT),),
        ),
    }
    check("additive-directory", base, additive, ())
    check("additive-jar", base, additive, (), use_jars=True)

    check("missing-class", base, {}, ("JVMABI_MISSING_CLASS",))

    missing_members_current = {"api/Sample": _fixture_class("api/Sample")}
    check(
        "missing-members",
        base,
        missing_members_current,
        (
            "JVMABI_MISSING_CONSTRUCTOR",
            "JVMABI_MISSING_FIELD",
            "JVMABI_MISSING_METHOD",
        ),
    )

    descriptor_baseline = {
        "api/Descriptors": _fixture_class(
            "api/Descriptors",
            fields=(("value", "I", ACC_PUBLIC),),
            methods=(
                ("<init>", "(I)V", ACC_PUBLIC),
                ("pick", "(I)I", ACC_PUBLIC),
            ),
        )
    }
    descriptor_current = {
        "api/Descriptors": _fixture_class(
            "api/Descriptors",
            fields=(("value", "J", ACC_PUBLIC),),
            methods=(
                ("<init>", "(J)V", ACC_PUBLIC),
                ("pick", "(J)I", ACC_PUBLIC),
            ),
        )
    }
    check(
        "changed-descriptors",
        descriptor_baseline,
        descriptor_current,
        (
            "JVMABI_CONSTRUCTOR_DESCRIPTOR_CHANGED",
            "JVMABI_FIELD_DESCRIPTOR_CHANGED",
            "JVMABI_METHOD_DESCRIPTOR_CHANGED",
        ),
    )

    visibility_baseline = {
        "api/Visibility": _fixture_class(
            "api/Visibility",
            fields=(("field", "I", ACC_PUBLIC),),
            methods=(("method", "()V", ACC_PROTECTED),),
        )
    }
    visibility_current = {
        "api/Visibility": _fixture_class(
            "api/Visibility",
            access=0,
            fields=(("field", "I", ACC_PROTECTED),),
            methods=(("method", "()V", ACC_PRIVATE),),
        )
    }
    check(
        "reduced-visibility",
        visibility_baseline,
        visibility_current,
        (
            "JVMABI_CLASS_VISIBILITY_REDUCED",
            "JVMABI_FIELD_VISIBILITY_REDUCED",
            "JVMABI_METHOD_VISIBILITY_REDUCED",
        ),
    )

    hierarchy_baseline = {
        "api/Child": _fixture_class(
            "api/Child",
            super_name="api/Parent",
            interfaces=("api/Marker",),
        )
    }
    hierarchy_current = {"api/Child": _fixture_class("api/Child")}
    check(
        "hierarchy-contract",
        hierarchy_baseline,
        hierarchy_current,
        ("JVMABI_INTERFACE_REMOVED", "JVMABI_SUPERCLASS_CHANGED"),
    )

    abstract_baseline = {
        "api/Service": _fixture_class(
            "api/Service",
            access=ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT,
            methods=(("existing", "()V", ACC_PUBLIC | ACC_ABSTRACT),),
        )
    }
    abstract_current = {
        "api/Service": _fixture_class(
            "api/Service",
            access=ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT,
            methods=(
                ("existing", "()V", ACC_PUBLIC | ACC_ABSTRACT),
                ("added", "()V", ACC_PUBLIC | ACC_ABSTRACT),
            ),
        )
    }
    check(
        "added-abstract-method",
        abstract_baseline,
        abstract_current,
        ("JVMABI_ADDED_ABSTRACT_METHOD",),
    )

    inherited_abstract_baseline = {
        "api/InheritedService": _fixture_class(
            "api/InheritedService",
            access=ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT,
            interfaces=("api/ExistingRequirement",),
        ),
        "api/ExistingRequirement": _fixture_class(
            "api/ExistingRequirement",
            access=ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT,
            methods=(("required", "()V", ACC_PUBLIC | ACC_ABSTRACT),),
        ),
    }
    inherited_abstract_current = {
        **inherited_abstract_baseline,
        "api/InheritedService": _fixture_class(
            "api/InheritedService",
            access=ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT,
            interfaces=("api/ExistingRequirement",),
            methods=(("required", "()V", ACC_PUBLIC | ACC_ABSTRACT),),
        ),
    }
    check(
        "existing-inherited-abstract-requirement",
        inherited_abstract_baseline,
        inherited_abstract_current,
        (),
    )

    added_superinterface_baseline = {
        "api/ExtendedService": _fixture_class(
            "api/ExtendedService",
            access=ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT,
        )
    }
    added_superinterface_current = {
        "api/ExtendedService": _fixture_class(
            "api/ExtendedService",
            access=ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT,
            interfaces=("api/NewRequirement",),
        ),
        "api/NewRequirement": _fixture_class(
            "api/NewRequirement",
            access=ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT,
            methods=(("required", "()V", ACC_PUBLIC | ACC_ABSTRACT),),
        ),
    }
    check(
        "added-superinterface-abstract-requirement",
        added_superinterface_baseline,
        added_superinterface_current,
        ("JVMABI_ADDED_ABSTRACT_METHOD",),
    )

    specific_abstract_baseline = {
        "api/SpecificService": _fixture_class(
            "api/SpecificService",
            access=ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT,
        )
    }
    specific_abstract_current = {
        "api/SpecificService": _fixture_class(
            "api/SpecificService",
            access=ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT,
            interfaces=("api/DefaultAncestor", "api/AbstractChild"),
        ),
        "api/DefaultAncestor": _fixture_class(
            "api/DefaultAncestor",
            access=ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT,
            methods=(("required", "()V", ACC_PUBLIC),),
        ),
        "api/AbstractChild": _fixture_class(
            "api/AbstractChild",
            access=ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT,
            interfaces=("api/DefaultAncestor",),
            methods=(("required", "()V", ACC_PUBLIC | ACC_ABSTRACT),),
        ),
    }
    check(
        "most-specific-interface-abstract-requirement",
        specific_abstract_baseline,
        specific_abstract_current,
        ("JVMABI_ADDED_ABSTRACT_METHOD",),
    )

    default_satisfaction_baseline = {
        "api/DefaultSatisfied": _fixture_class(
            "api/DefaultSatisfied",
            access=ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT,
            interfaces=("api/DefaultProvider",),
        ),
        "api/DefaultProvider": _fixture_class(
            "api/DefaultProvider",
            access=ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT,
            methods=(("required", "()V", ACC_PUBLIC),),
        ),
    }
    default_satisfaction_current = {
        **default_satisfaction_baseline,
        "api/DefaultSatisfied": _fixture_class(
            "api/DefaultSatisfied",
            access=ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT,
            interfaces=("api/DefaultProvider", "api/AbstractProvider"),
        ),
        "api/AbstractProvider": _fixture_class(
            "api/AbstractProvider",
            access=ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT,
            methods=(("required", "()V", ACC_PUBLIC | ACC_ABSTRACT),),
        ),
    }
    check(
        "existing-default-satisfies-added-abstract-parent",
        default_satisfaction_baseline,
        default_satisfaction_current,
        (),
    )

    concrete_satisfaction_baseline = {
        "api/AbstractBase": _fixture_class(
            "api/AbstractBase",
            access=ACC_PUBLIC | ACC_ABSTRACT,
            methods=(("required", "()V", ACC_PUBLIC),),
        )
    }
    concrete_satisfaction_current = {
        "api/AbstractBase": _fixture_class(
            "api/AbstractBase",
            access=ACC_PUBLIC | ACC_ABSTRACT,
            interfaces=("api/ClassRequirement",),
            methods=(("required", "()V", ACC_PUBLIC),),
        ),
        "api/ClassRequirement": _fixture_class(
            "api/ClassRequirement",
            access=ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT,
            methods=(("required", "()V", ACC_PUBLIC | ACC_ABSTRACT),),
        ),
    }
    check(
        "class-concrete-method-satisfies-added-interface",
        concrete_satisfaction_baseline,
        concrete_satisfaction_current,
        (),
    )

    final_baseline = {
        "api/Extensible": _fixture_class(
            "api/Extensible",
            fields=(("field", "I", ACC_PUBLIC),),
            methods=(("method", "()V", ACC_PUBLIC),),
        )
    }
    final_current = {
        "api/Extensible": _fixture_class(
            "api/Extensible",
            access=ACC_PUBLIC | ACC_FINAL,
            fields=(("field", "I", ACC_PUBLIC | ACC_FINAL),),
            methods=(("method", "()V", ACC_PUBLIC | ACC_FINAL),),
        )
    }
    check(
        "final-restrictions",
        final_baseline,
        final_current,
        (
            "JVMABI_CLASS_BECAME_FINAL",
            "JVMABI_FIELD_BECAME_FINAL",
            "JVMABI_METHOD_BECAME_FINAL",
        ),
    )

    concrete_method_baseline = {
        "api/Concrete": _fixture_class(
            "api/Concrete", methods=(("run", "()V", ACC_PUBLIC),)
        )
    }
    concrete_method_current = {
        "api/Concrete": _fixture_class(
            "api/Concrete",
            access=ACC_PUBLIC | ACC_ABSTRACT,
            methods=(("run", "()V", ACC_PUBLIC | ACC_ABSTRACT),),
        )
    }
    check(
        "became-abstract",
        concrete_method_baseline,
        concrete_method_current,
        ("JVMABI_CLASS_BECAME_ABSTRACT", "JVMABI_METHOD_BECAME_ABSTRACT"),
    )

    static_baseline = {
        "api/StaticKinds": _fixture_class(
            "api/StaticKinds",
            fields=(("field", "I", ACC_PUBLIC),),
            methods=(("method", "()V", ACC_PUBLIC),),
        )
    }
    static_current = {
        "api/StaticKinds": _fixture_class(
            "api/StaticKinds",
            fields=(("field", "I", ACC_PUBLIC | ACC_STATIC),),
            methods=(("method", "()V", ACC_PUBLIC | ACC_STATIC),),
        )
    }
    check(
        "static-kind-changes",
        static_baseline,
        static_current,
        ("JVMABI_FIELD_STATIC_CHANGED", "JVMABI_METHOD_STATIC_CHANGED"),
    )

    protected_nested_baseline = {
        "api/Outer": _fixture_class("api/Outer"),
        "api/Outer$Nested": _fixture_class(
            "api/Outer$Nested",
            inner_access=ACC_PROTECTED | ACC_STATIC,
            outer_name="api/Outer",
        ),
    }
    protected_nested_current = {"api/Outer": _fixture_class("api/Outer")}
    check(
        "protected-nested-class",
        protected_nested_baseline,
        protected_nested_current,
        ("JVMABI_MISSING_CLASS",),
    )

    synthetic_baseline = {
        "api/SyntheticSurface": _fixture_class(
            "api/SyntheticSurface",
            methods=(
                ("$anonfun$body$1", "()V", ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC),
                ("bridge", "(Ljava/lang/Object;)Ljava/lang/Object;", ACC_PUBLIC | ACC_SYNTHETIC | ACC_BRIDGE),
            ),
        ),
        "api/SyntheticClass": _fixture_class(
            "api/SyntheticClass", access=ACC_PUBLIC | ACC_SYNTHETIC
        ),
    }
    synthetic_current = {
        "api/SyntheticSurface": _fixture_class("api/SyntheticSurface")
    }
    check(
        "synthetic-implementation-filter",
        synthetic_baseline,
        synthetic_current,
        ("JVMABI_MISSING_METHOD",),
    )

    reflective_cache_descriptor = SCALA_REFLECTIVE_CALL_CACHE_DESCRIPTOR
    reflective_cache_baseline = {
        "api/StructuralCalls": _fixture_class(
            "api/StructuralCalls",
            methods=((
                "reflMethod$Method4",
                reflective_cache_descriptor,
                ACC_PUBLIC | ACC_STATIC,
            ),),
        )
    }
    reflective_cache_current = {
        "api/StructuralCalls": _fixture_class(
            "api/StructuralCalls",
            methods=((
                "reflMethod$Method5",
                reflective_cache_descriptor,
                ACC_PUBLIC | ACC_STATIC,
            ),),
        )
    }
    check(
        "scala-reflective-call-cache-renumbering",
        reflective_cache_baseline,
        reflective_cache_current,
        (),
    )

    reflective_cache_near_misses = {
        "api/StructuralNearMisses": _fixture_class(
            "api/StructuralNearMisses",
            methods=(
                (
                    "reflMethod$MethodX",
                    reflective_cache_descriptor,
                    ACC_PUBLIC | ACC_STATIC,
                ),
                ("reflMethod$Method6", "()V", ACC_PUBLIC | ACC_STATIC),
                (
                    "reflMethod$Method7",
                    reflective_cache_descriptor,
                    ACC_PUBLIC,
                ),
                (
                    "reflMethod$Method8",
                    reflective_cache_descriptor,
                    ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC,
                ),
            ),
        )
    }
    check(
        "scala-reflective-call-cache-near-misses-remain-contractual",
        reflective_cache_near_misses,
        {"api/StructuralNearMisses": _fixture_class("api/StructuralNearMisses")},
        ("JVMABI_MISSING_METHOD",) * 4,
    )

    synthetic_linkage_baseline = {
        "api/SyntheticLinkage": _fixture_class(
            "api/SyntheticLinkage",
            methods=(
                ("traitBody$", "(Lapi/SyntheticLinkage;)V", ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC),
                ("api$SyntheticLinkage$$$outer", "()Lapi/Outer;", ACC_PUBLIC | ACC_SYNTHETIC),
            ),
        )
    }
    synthetic_linkage_current = {
        "api/SyntheticLinkage": _fixture_class("api/SyntheticLinkage")
    }
    check(
        "synthetic-linkage-members-remain-contractual",
        synthetic_linkage_baseline,
        synthetic_linkage_current,
        ("JVMABI_MISSING_METHOD", "JVMABI_MISSING_METHOD"),
    )

    print(f"JVMABI_SELF_TEST_OK cases={cases}")
    return 0


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--baseline",
        action="append",
        default=[],
        metavar="DIR_OR_JAR",
        help="baseline class directory or JAR; repeatable",
    )
    parser.add_argument(
        "--current",
        action="append",
        default=[],
        metavar="DIR_OR_JAR",
        help="current class directory or JAR; repeatable",
    )
    parser.add_argument(
        "--label",
        help="artifact label included in diagnostics (for example core)",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="run isolated class parser and policy tests",
    )
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = _parser()
    arguments = parser.parse_args(argv)
    if arguments.self_test:
        if arguments.baseline or arguments.current:
            parser.error("--self-test cannot be combined with artifact inputs")
        try:
            return run_self_tests()
        except (AssertionError, ClassFormatError, OSError) as error:
            print(f"JVMABI_SELF_TEST_FAILED: {error}", file=sys.stderr)
            return 2

    if not arguments.baseline or not arguments.current:
        parser.error("at least one --baseline and one --current input are required")

    try:
        baseline = load_classes([Path(value) for value in arguments.baseline])
        current = load_classes([Path(value) for value in arguments.current])
        diagnostics = compare_classes(baseline, current)
    except (ClassFormatError, OSError) as error:
        print(f"JVMABI_INPUT_ERROR: {error}", file=sys.stderr)
        return 2

    if diagnostics:
        for diagnostic in diagnostics:
            print(diagnostic.render(arguments.label), file=sys.stderr)
        print(
            "JVMABI_INCOMPATIBLE: "
            f"failures={len(diagnostics)} "
            f"baseline_api_classes={sum(_is_api_class(name, baseline) for name in baseline)}",
            file=sys.stderr,
        )
        return 1

    label = f" artifact={json.dumps(arguments.label)}" if arguments.label else ""
    baseline_count = sum(_is_api_class(name, baseline) for name in baseline)
    print(
        f"JVMABI_OK:{label} baseline_api_classes={baseline_count} "
        f"current_classes={len(current)}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
