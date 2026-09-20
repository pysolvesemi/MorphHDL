"""Test-only balanced signed-cast inspection; never used by production analysis."""
import re


def has_nested_signed_cast(text: str) -> bool:
    # Strip comments and quoted strings, then track actual parenthesis nesting.
    # Counting two occurrences before a semicolon incorrectly accepts siblings.
    text = re.sub(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"', '', text, flags=re.S)
    stack = []
    pending_signed = False
    for token in re.findall(r'\$signed\b|[A-Za-z_$][A-Za-z0-9_$]*|[^\s]', text):
        if token == '$signed':
            pending_signed = True
        elif token == '(':
            if pending_signed and any(stack):
                return True
            stack.append(pending_signed)
            pending_signed = False
        elif token == ')':
            if stack:
                stack.pop()
            pending_signed = False
        else:
            pending_signed = False
    return False


def self_test() -> None:
    assert has_nested_signed_cast('$signed($signed(a))')
    assert has_nested_signed_cast('$signed((a + $signed(b)))')
    assert not has_nested_signed_cast('$signed(a) + $signed(b);')
    assert not has_nested_signed_cast('($signed(a)) + ($signed(b));')
    assert not has_nested_signed_cast('// $signed($signed(a))\n$unsigned(a);')
    assert not has_nested_signed_cast('"$signed($signed(a))"')
    assert not has_nested_signed_cast('/* $signed($signed(a)) */ a;')


if __name__ == '__main__':
    self_test()
    print('Balanced signed-cast detector: nested, sibling and comment self-tests PASS')
