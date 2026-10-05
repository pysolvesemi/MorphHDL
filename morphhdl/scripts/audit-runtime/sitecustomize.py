"""Opt-in Git transport for the authenticated historical source-audit wrapper."""
import os
if os.environ.get('MORPHHDL_AUDIT_GIT_BATCH') == '1':
    import audit_git_batch
    audit_git_batch.install()
