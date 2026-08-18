package displaycontroller.sanity

/** Fixed elaboration-time configuration for the Phase 1 sanity shell. */
final case class DisplayControllerSanityShellConfig(
    profileId: String,
    axilAddressWidth: Int,
    axilDataWidth: Int,
    axisDataWidth: Int,
    axisUserWidth: Int,
    ppc: Int,
    axiAddressWidth: Int,
    axiDataWidth: Int,
    axiIdWidth: Int,
    dpiComponentWidth: Int,
    nativeHdlParameters: Boolean,
    moduleName: String,
    generatedOutputPath: String
) {
  def validate(): this.type = {
    if (profileId != DisplayControllerSanityShellConfig.ProfileId)
      throw new IllegalArgumentException("DCS_CONFIG_PROFILE")
    if (axilAddressWidth != 12)
      throw new IllegalArgumentException("DCS_CONFIG_AXIL_ADDRESS_WIDTH")
    if (axilDataWidth != 32)
      throw new IllegalArgumentException("DCS_CONFIG_AXIL_DATA_WIDTH")
    if (axisDataWidth != 32)
      throw new IllegalArgumentException("DCS_CONFIG_AXIS_DATA_WIDTH")
    if (axisUserWidth != 1)
      throw new IllegalArgumentException("DCS_CONFIG_AXIS_USER_WIDTH")
    if (ppc != 1)
      throw new IllegalArgumentException("DCS_CONFIG_PPC")
    if (axiAddressWidth != 32)
      throw new IllegalArgumentException("DCS_CONFIG_AXI_ADDRESS_WIDTH")
    if (axiDataWidth != 64)
      throw new IllegalArgumentException("DCS_CONFIG_AXI_DATA_WIDTH")
    if (axiIdWidth != 4)
      throw new IllegalArgumentException("DCS_CONFIG_AXI_ID_WIDTH")
    if (dpiComponentWidth != 8)
      throw new IllegalArgumentException("DCS_CONFIG_DPI_COMPONENT_WIDTH")
    if (nativeHdlParameters)
      throw new IllegalArgumentException("DCS_CONFIG_NATIVE_PARAMETER_UNSUPPORTED")
    if (moduleName != DisplayControllerSanityShellConfig.ModuleName)
      throw new IllegalArgumentException("DCS_CONFIG_MODULE_NAME")
    if (generatedOutputPath != DisplayControllerSanityShellConfig.GeneratedOutputPath)
      throw new IllegalArgumentException("DCS_CONFIG_OUTPUT_PATH")
    this
  }
}

object DisplayControllerSanityShellConfig {
  final val ProfileId = "sanity_p1_ppc1_axil32_axi64_rgb888"
  final val ModuleName = "DisplayControllerSanityShell"
  final val GeneratedOutputPath = "hw/gen/sanity"

  def sanity: DisplayControllerSanityShellConfig =
    DisplayControllerSanityShellConfig(
      profileId = ProfileId,
      axilAddressWidth = 12,
      axilDataWidth = 32,
      axisDataWidth = 32,
      axisUserWidth = 1,
      ppc = 1,
      axiAddressWidth = 32,
      axiDataWidth = 64,
      axiIdWidth = 4,
      dpiComponentWidth = 8,
      nativeHdlParameters = false,
      moduleName = ModuleName,
      generatedOutputPath = GeneratedOutputPath
    )
}

/**
  * Negative-profile probe used by the target validation flow.
  * Every accepted argument deliberately mutates one approved fixed-profile field.
  */
object DisplayControllerSanityShellConfigProbe extends App {
  if (args.length != 1)
    throw new IllegalArgumentException("DCS_CONFIG_PROBE_ARGUMENT")

  val legal = DisplayControllerSanityShellConfig.sanity
  val invalid = args(0) match {
    case "axil-address-width"    => legal.copy(axilAddressWidth = 16)
    case "axil-data-width"       => legal.copy(axilDataWidth = 64)
    case "axis-data-width"       => legal.copy(axisDataWidth = 64)
    case "axis-user-width"       => legal.copy(axisUserWidth = 2)
    case "ppc"                   => legal.copy(ppc = 4)
    case "axi-address-width"     => legal.copy(axiAddressWidth = 64)
    case "axi-data-width"        => legal.copy(axiDataWidth = 32)
    case "axi-id-width"          => legal.copy(axiIdWidth = 8)
    case "dpi-component-width"   => legal.copy(dpiComponentWidth = 10)
    case "native-hdl-parameters" => legal.copy(nativeHdlParameters = true)
    case "module-name"           => legal.copy(moduleName = "DisplayControllerSanityShellChanged")
    case "output-path"           => legal.copy(generatedOutputPath = "hw/gen/changed")
    case _                        => throw new IllegalArgumentException("DCS_CONFIG_PROBE_UNKNOWN_CASE")
  }

  invalid.validate()
  throw new IllegalStateException("DCS_CONFIG_PROBE_UNEXPECTED_ACCEPT")
}
