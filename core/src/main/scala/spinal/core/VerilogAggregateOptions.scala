package spinal.core

/** Generation-local publication choices. Defaults preserve native concrete elaboration. */
final case class VerilogAggregateOptions(
    preserveConstantVecs: Boolean = false,
    preserveConstantLoops: Boolean = false,
    vecLayout: VerilogAggregateOptions.Layout = VerilogAggregateOptions.PackedVector
)

object VerilogAggregateOptions {
  sealed trait Layout
  case object PackedVector extends Layout
  case object UnpackedArray extends Layout

  def of(config: SpinalConfig): VerilogAggregateOptions =
    config.flags.collectFirst { case value: VerilogAggregateOptions => value }
      .getOrElse(VerilogAggregateOptions())

  private[spinal] def current: VerilogAggregateOptions = of(GlobalData.get.config)
}
