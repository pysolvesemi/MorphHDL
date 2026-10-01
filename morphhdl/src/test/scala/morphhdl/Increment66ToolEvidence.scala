package morphhdl

import java.nio.file.{Files,Path,Paths,StandardOpenOption}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import scala.collection.JavaConverters._
import scala.sys.process.{Process,ProcessLogger}

/** Optional source-bound tool receipts; this does not change tool invocation,
  * assertions, proof engines or simulator behavior.
  */
object Increment66ToolEvidence {
  private lazy val destination = sys.env.get("MORPHDL_INCREMENT66_EVIDENCE").map(value => Paths.get(value).toAbsolutePath)
  private lazy val head = Process(Seq("git","rev-parse","HEAD")).!!.trim
  private def sha(bytes:Array[Byte]):String=MessageDigest.getInstance("SHA-256").digest(bytes).map(x=>f"${x&255}%02x").mkString
  private def quote(value:String):String="\""+value.flatMap {
    case '\\'=>"\\\\"; case '"'=>"\\\""; case '\n'=>"\\n"; case '\r'=>"\\r"; case '\t'=>"\\t"
    case c if c<' '=>f"\\u${c.toInt}%04x"; case c=>c.toString
  }+"\""
  def run(directory:Path,arguments:Seq[String]):(Int,String)={
    val inputs=destination.map { root =>
      Files.createDirectories(root.resolve("inputs"))
      val stream=Files.walk(directory)
      try stream.iterator().asScala.filter(Files.isRegularFile(_)).filter { path =>
        Seq(".v",".sv",".ys",".sby").exists(path.toString.endsWith)
      }.toVector.sortBy(_.toString).map { path =>
        val bytes=Files.readAllBytes(path)
        val digest=sha(bytes)
        synchronized { Files.write(root.resolve("inputs").resolve(digest),bytes) }
        directory.relativize(path).toString -> digest
      }
      finally stream.close()
    }.getOrElse(Vector.empty)
    val output=new StringBuilder
    val status=Process(arguments,directory.toFile).!(ProcessLogger(x=>output.append(x).append('\n'),x=>output.append(x).append('\n')))
    val text=output.toString
    destination.foreach { root => synchronized {
      Files.createDirectories(root.resolve("logs"))
      val digest=sha(text.getBytes(UTF_8))
      Files.write(root.resolve("logs").resolve(digest+".log"),text.getBytes(UTF_8))
      val record="{"+Seq(
        "source"->quote(head),"scala"->quote(scala.util.Properties.versionNumberString),
        "directory"->quote(directory.toString),"arguments"->arguments.map(quote).mkString("[",",","]"),
        "exit"->status.toString,"log_sha256"->quote(digest),
        "inputs"->inputs.map { case(path,hash)=>quote(path)+":"+quote(hash) }.mkString("{",",","}")
      ).map { case(key,value)=>quote(key)+":"+value }.mkString(",")+"}\n"
      Files.write(root.resolve("tools.jsonl"),record.getBytes(UTF_8),StandardOpenOption.CREATE,StandardOpenOption.APPEND)
    }}
    status->text
  }
}
