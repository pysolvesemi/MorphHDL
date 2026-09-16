"""Adversarial controls for native legality evidence, not alternative DUTs."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from check_symbolic_publication import check_guard, failed_simulation


class SymbolicPolicyEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.dut = self.root / 'dut.v'
        self.hardware = 'module DUT(input wire data, output wire observed); assign observed=data;\n'
        self.guard = '`ifndef SYNTHESIS\ninitial $fatal(1,"%s","invalid");\n`endif\n'

    def test_guarded_diagnostics_leave_hardware_visible(self):
        self.dut.write_text(self.hardware + self.guard + 'endmodule\n')
        check_guard(self.dut, True)

    def test_missing_guard_fails(self):
        self.dut.write_text(self.hardware + '$error("unguarded"); endmodule\n')
        with self.assertRaisesRegex(RuntimeError, 'not protected'):
            check_guard(self.dut, True)

    def test_extra_unguarded_diagnostic_fails(self):
        self.dut.write_text(self.hardware + self.guard + '$fatal(1); endmodule\n')
        with self.assertRaisesRegex(RuntimeError, 'escaped'):
            check_guard(self.dut, True)

    def test_hardware_inside_simulation_guard_fails(self):
        self.dut.write_text('`ifndef SYNTHESIS\n' + self.hardware + '$fatal(1,"invalid");\n`endif\n')
        with self.assertRaisesRegex(RuntimeError, 'removed hardware'):
            check_guard(self.dut, True)

    def test_proven_positive_width_must_not_gain_clamp_diagnostic(self):
        self.dut.write_text(self.hardware + self.guard + 'endmodule\n')
        with self.assertRaisesRegex(RuntimeError, 'unnecessary'):
            check_guard(self.dut, False)

    def test_error_without_fatal_is_rejected(self):
        self.dut.write_text(self.hardware + '`ifndef SYNTHESIS\ninitial $error("invalid");\n`endif\nendmodule\n')
        with self.assertRaisesRegex(RuntimeError, 'not protected'):
            check_guard(self.dut, True)

    def test_duplicate_error_then_fatal_is_rejected(self):
        self.dut.write_text(self.hardware + self.guard.replace('initial ', 'initial $error("invalid"); initial ') + 'endmodule\n')
        with self.assertRaisesRegex(RuntimeError, 'fatal only'):
            check_guard(self.dut, True)

    def test_fatal_in_comment_does_not_count(self):
        self.dut.write_text(self.hardware + '`ifndef SYNTHESIS\n// $fatal(1,"invalid");\n`endif\nendmodule\n')
        with self.assertRaisesRegex(RuntimeError, 'not protected'):
            check_guard(self.dut, True)

    def test_diagnostic_names_inside_message_are_literal(self):
        self.dut.write_text(self.hardware + self.guard.replace('"invalid"', '"literal $error and 100% %d"') + 'endmodule\n')
        check_guard(self.dut, True)

    def test_execution_must_not_continue_after_failed_require(self):
        with self.assertRaisesRegex(RuntimeError, 'not rejected'):
            self.result(1, 'legality rejected; ILLEGAL_EXECUTION_CONTINUED')

    def result(self, code, message):
        commands = []
        failed_simulation([sys.executable, '-c', f'print({message!r}); raise SystemExit({code})'],
                          self.root, self.root / 'run.log', 'legality rejected', commands)
        return commands

    def test_zero_exit_does_not_count_as_invalid_tuple_rejection(self):
        with self.assertRaisesRegex(RuntimeError, 'not rejected'):
            self.result(0, 'legality rejected')

    def test_unrelated_crash_does_not_count_as_legality_rejection(self):
        with self.assertRaisesRegex(RuntimeError, 'not rejected'):
            self.result(1, 'unrelated crash')

    def test_nonzero_exit_and_specific_diagnostic_are_both_required(self):
        commands = self.result(1, 'legality rejected')
        self.assertEqual(commands[0]['returncode'], 1)
        self.assertIn('invalid-parameter', commands[0]['expected'])


if __name__ == '__main__':
    unittest.main()
