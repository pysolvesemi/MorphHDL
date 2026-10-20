package spinal.core

/** Verilog-2001 sizing for an already authenticated constant declaration.
  * This formatter grants no ownership, domain or lineage authority.
  */
private[spinal] object NativeParameterValueSizing {
  def reference(name:String, sourceWidth:Int, width:Option[ElaborationIntegerExpression],
      defaultWidth:Int, signed:Boolean, minimumValue:BigInt):String = {
    val text=width.map(_.verilog).getOrElse(defaultWidth.toString)
    val minimum=width.map(_.minimum).getOrElse(BigInt(defaultWidth))
    val maximum=width.map(_.maximum).getOrElse(BigInt(defaultWidth))
    val extension=if(signed && minimumValue<0)s"$name[${sourceWidth-1}]" else "1'b0"
    if(minimum==sourceWidth && maximum==sourceWidth)name
    else if(maximum<=sourceWidth)s"$name[($text)-1:0]"
    else if(minimum>=sourceWidth)s"{{(($text)-$sourceWidth){$extension}},$name}"
    else s"{{((($text)>$sourceWidth)?(($text)-$sourceWidth):0){$extension}},$name[((($text)<$sourceWidth)?($text):$sourceWidth)-1:0]}"
  }
}
