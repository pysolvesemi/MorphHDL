"""Tests of the regression DRIVER only, not MorphHDL or a Verilog simulator."""
import tempfile
import unittest
from pathlib import Path

from check import (Case, Fixture, RECORD, WIDTH, PARENT, PROJECTION, ALWAYS, fixture_ports, make_testbench,
                   require_identical, require_single_module, require_unchanged)


class DriverTests(unittest.TestCase):
    def test_exact_required_override_matrix(self):
        self.assertEqual([case.values for case in WIDTH.cases if not case.defaults], [
            (32, 32), (1, 2), (1, 64), (2048, 2), (2048, 64), (120, 8), (17, 32)])

    def test_expected_two_parameter_widths(self):
        self.assertEqual([WIDTH.width(case) for case in WIDTH.cases],
                         [64, 3, 65, 2050, 2112, 128, 49, 64])

    def test_realistic_record_widths(self):
        self.assertEqual([RECORD.width(case) for case in RECORD.cases],
                         [92, 28, 105, 2075, 2152, 155, 75, 92])

    def test_actual_dut_width_and_highest_bit_checked(self):
        bench = make_testbench(WIDTH)
        for index in range(len(WIDTH.cases)):
            self.assertIn(f"$bits(dut_{index}.record)", bench)
            self.assertIn(f"bit_{index}<WIDTH_{index}", bench)
            self.assertIn(f"record_{index}[bit_{index}] = 1'b1", bench)
            self.assertIn(f"dut_{index}.record !== record_{index}", bench)
            self.assertIn(f"observed_{index} !== 1'b1", bench)
            self.assertIn(f"observed_{index} !== 1'b0", bench)

    def test_single_dut_module_type_and_defaults(self):
        bench = make_testbench(WIDTH)
        self.assertEqual(bench.count("  RecordWidth #("), 7)
        self.assertIn("  RecordWidth dut_7 (", bench)
        self.assertNotIn("PROFILE", bench)

    def test_fixture_parameters_override_independently(self):
        bench = make_testbench(WIDTH)
        self.assertIn(".DATA_BITS(1), .GENERATION_BITS(64)", bench)
        self.assertIn(".DATA_BITS(2048), .GENERATION_BITS(2)", bench)

    def test_deterministic_testbench_construction(self):
        self.assertEqual(make_testbench(WIDTH), make_testbench(WIDTH))
        self.assertEqual(make_testbench(RECORD), make_testbench(RECORD))

    def test_bad_case_arity_is_rejected(self):
        with self.assertRaises(ValueError):
            WIDTH.width(Case((1,)))

    def test_child_actual_ports_and_parameters_are_checked(self):
        bench = make_testbench(PARENT, "din")
        for index in range(len(PARENT.cases)):
            self.assertIn(f"$bits(dut_{index}.child.din)", bench)
            for name in PARENT.parameters:
                self.assertIn(f"dut_{index}.child.{name} !==", bench)

    def test_projection_checks_kept_and_discarded_highest_bits(self):
        bench = make_testbench(PROJECTION, "din")
        self.assertIn("((bit_0 < WIDTH_0-1) ? 1'b1 : 1'b0)", bench)
        self.assertIn("if (observed_1 !== 1'b1)", bench)
        self.assertEqual(PROJECTION.cases[-1].values, (1, 32))
        self.assertEqual(len(ALWAYS.cases), 33)

    def test_port_parser_accepts_native_reg_output_and_selects_parent(self):
        with tempfile.TemporaryDirectory() as name:
            source = Path(name) / "source.v"
            source.write_text("module Top (input wire [3:0] din, output reg observed); endmodule\n"
                              "module Child (input wire [3:0] other, output wire result); endmodule\n")
            self.assertEqual(fixture_ports(source, "Top"), ("din", "observed"))
            with self.assertRaises(RuntimeError):
                fixture_ports(source)

    def test_changed_published_bytes_fail(self):
        with tempfile.TemporaryDirectory() as name:
            first, second = Path(name) / "first.v", Path(name) / "second.v"
            first.write_bytes(b"module Toy; endmodule\n")
            second.write_bytes(first.read_bytes())
            sha = require_identical(first, second)
            require_unchanged(first, sha)
            second.write_bytes(first.read_bytes() + b" ")
            with self.assertRaises(RuntimeError):
                require_identical(first, second)
            with self.assertRaises(RuntimeError):
                require_unchanged(second, sha)

    def test_empty_or_multiple_modules_fail(self):
        with tempfile.TemporaryDirectory() as name:
            first, second = Path(name) / "first.v", Path(name) / "second.v"
            first.write_bytes(b"")
            second.write_bytes(b"")
            with self.assertRaises(RuntimeError):
                require_identical(first, second)
            first.write_text('// module ignored\nmodule Toy; initial $display("module NotADefinition // text"); endmodule\n')
            require_single_module(first, "Toy")
            first.write_text("module Toy; endmodule\nmodule Extra; endmodule\n")
            with self.assertRaises(RuntimeError):
                require_single_module(first, "Toy")


if __name__ == "__main__":
    unittest.main(verbosity=2)
