package displaycontroller.sanity

import spinal.core._

/** Generates the one approved fixed-profile Verilog artifact. */
object DisplayControllerSanityShellVerilog extends App {
  val config = DisplayControllerSanityShellConfig.sanity.validate()

  SpinalConfig(
    targetDirectory = config.generatedOutputPath,
    defaultConfigForClockDomains = ClockDomainConfig(
      resetActiveLevel = LOW
    ),
    onlyStdLogicVectorAtTopLevelIo = false
  ).generateVerilog(DisplayControllerSanityShell(config))
}
