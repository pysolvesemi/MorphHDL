package spinal.core.internals

import java.nio.file.Files
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class TypedBalancedReductionCertifiedCallbackPolicyTests extends AnyFunSuite {
  private def admit(callback: AnyRef): TypedBalancedReductionCaptureSchema =
    TypedBalancedReductionCertifiedCallbackPolicy.requireSupportedOperator(callback)

  private def reject(callback: AnyRef): Unit = {
    val error = intercept[IllegalArgumentException](admit(callback))
    assert(error.getMessage.contains("MORPH-REDUCE-BALANCED-"), error.getMessage)
  }

  private def generate(body: => Component): Unit =
    SpinalConfig(targetDirectory = Files.createTempDirectory("reduce-certified-policy-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(body)

  test("multi-node native expressions and order-sensitive subtraction are inspected without execution") {
    Vector[AnyRef](
      (a: UInt, b: UInt) => (a + b) ^ a,
      (a: UInt, b: UInt) => a - b,
      (a: UInt, b: UInt) => Mux(a > b, a - b, a + b),
      (a: Bits, b: Bits) => (a ## b).resize(8),
      (a: UInt, b: UInt) => Mux(a(0), a, b),
      (a: UInt, b: UInt) => (a(0, 1 bits).resize(8) ^ b)
    ).foreach(admit)
  }

  test("complete pure user helper call graphs including differently-shaped signatures are admitted") {
    admit((a: UInt, b: UInt) => CertifiedReductionPureHelpers.combine(a, b))
    admit((a: UInt, b: UInt) => CertifiedReductionPureHelpers.choose(a > b, a, b))
  }

  test("native local temporary and nested when bodies retain fresh-target provenance") {
    admit((a: UInt, b: UInt) => {
      val result = UInt()
      result := b
      when(a > b) { result := a }
      result
    })
    admit((a: UInt, b: UInt) => {
      val result = UInt()
      when(a > b) { result := a } otherwise { result := b }
      result
    })
  }

  test("external writes reject before effects even when hidden behind helpers or extraction aliases") {
    reject((a: UInt, b: UInt) => { a := b; a })
    reject((a: UInt, b: UInt) => CertifiedReductionPureHelpers.write(a, b))
    reject((a: UInt, b: UInt) => { a.msb := b.msb; a })
    reject((a: UInt, b: UInt) => { when(a > b) { a := b }; a })
  }

  test("unproved helper initialization is rejected before its initializer executes") {
    CertifiedReductionHostState.calls = 0
    reject((a: UInt, b: UInt) => CertifiedReductionEffectfulInitializer.combine(a, b))
    assert(CertifiedReductionHostState.calls == 0)
  }

  test("stateful helpers, recursion and runtime witness decisions reject without execution") {
    CertifiedReductionHostState.calls = 0
    reject((a: UInt, b: UInt) => CertifiedReductionPureHelpers.effect(a, b))
    reject((a: UInt, b: UInt) => CertifiedReductionPureHelpers.recursive(a, b))
    reject((a: UInt, b: UInt) => if (a.getWidth == 8) a else b)
    reject((a: UInt, b: UInt) => if (System.nanoTime() == 0) a else b)
    assert(CertifiedReductionHostState.calls == 0)
  }

  test("foreign static helper superclass and default-interface initialization reject before effects") {
    CertifiedCallbackInitializerFixture.calls = 0
    reject((a: UInt, b: UInt) => CertifiedCallbackInitializerFixture.StaticHelper.combine(a, b))
    reject((a: UInt, b: UInt) => CertifiedCallbackInitializerFixture.InterfaceHelper.combine(a, b))
    assert(CertifiedCallbackInitializerFixture.calls == 0)
  }

  test("unsupported class resource versions reject with a callback diagnostic before execution") {
    admit(CertifiedReductionClassVersionFixture.operator)
    val owner = "spinal.core.internals.CertifiedReductionClassVersionFixture$"
    val resource = owner.replace('.', '/') + ".class"
    val parent = getClass.getClassLoader
    val stream = parent.getResourceAsStream(resource)
    val output = new ByteArrayOutputStream
    try {
      val buffer = new Array[Byte](4096)
      var length = stream.read(buffer)
      while (length >= 0) {
        output.write(buffer, 0, length)
        length = stream.read(buffer)
      }
    } finally stream.close()
    val original = output.toByteArray
    val unsupported = original.clone()
    // The JVM loads the valid original class. Its inspection resource reports
    // a future class version, independent of the JDK running this regression.
    unsupported(6) = 0x7f.toByte
    unsupported(7) = 0xff.toByte
    val loader = new ClassLoader(parent) {
      override def loadClass(name: String, resolve: Boolean): Class[_] = synchronized {
        if (name != owner) super.loadClass(name, resolve)
        else {
          val loaded = Option(findLoadedClass(name)).getOrElse(defineClass(name, original, 0, original.length))
          if (resolve) resolveClass(loaded)
          loaded
        }
      }
      override def getResourceAsStream(name: String): InputStream =
        if (name == resource) new ByteArrayInputStream(unsupported) else super.getResourceAsStream(name)
    }
    val fixture = loader.loadClass(owner)
    val callback = fixture.getMethod("operator").invoke(fixture.getField("MODULE$").get(null))
    val error = intercept[IllegalArgumentException](admit(callback))
    assert(error.getMessage.contains("MORPH-REDUCE-BALANCED-CALLBACK-UNSUPPORTED"))
    assert(error.getMessage.contains("exact class bytes cannot be inspected for " + owner.replace('.', '/')))
  }

  test("module-shaped helper virtual dispatch cannot replace an inspected pure body") {
    val original = CertifiedCallbackMutableModuleFixture.Module$.MODULE$
    CertifiedCallbackMutableModuleFixture.calls = 0
    CertifiedCallbackMutableModuleFixture.Module$.MODULE$ =
      new CertifiedCallbackMutableModuleFixture.Replacement
    try {
      reject((a: UInt, b: UInt) => CertifiedCallbackMutableModuleFixture.Module$.MODULE$.combine(a, b))
      assert(CertifiedCallbackMutableModuleFixture.calls == 0)
    } finally {
      CertifiedCallbackMutableModuleFixture.Module$.MODULE$ = original
    }
  }

  test("mutable host closure slots and opaque function objects cannot acquire schema entries") {
    var calls = 0
    reject((a: UInt, b: UInt) => { calls += 1; a + b })
    reject(new Function2[UInt, UInt, UInt] {
      override def apply(a: UInt, b: UInt): UInt = a + b
    })
    assert(calls == 0)
  }

  test("explicit schema retains independent input identities and immutable native typed configuration") {
    generate(new Component {
      val first = in UInt(8 bits)
      val second = in UInt(8 bits)
      val output = out UInt(8 bits)
      val inspect = {
        val biasA = first
        val biasB = second
        val width = ElabInt.literal(8)
        val callback = (a: UInt, b: UInt) => ((a + biasA) ^ (b + biasB)).resize(width)
        val schema = admit(callback)
        assert(schema.hardwareInputs.size == 2)
        assert(schema.hardwareInputs.exists(_ eq first))
        assert(schema.hardwareInputs.exists(_ eq second))
        assert(schema.configurations.size == 1 && (schema.configurations.head eq width))
        schema.validateBindings()
        output := first ^ second
      }
    })
  }

  test("captured hardware writes and typed witness reads reject before callback execution") {
    generate(new Component {
      val input = in UInt(8 bits)
      val output = out UInt(8 bits)
      val inspect = {
        val bias = input
        val width = ElabInt.literal(8)
        reject((a: UInt, b: UInt) => { bias := a; b })
        reject((a: UInt, b: UInt) => if (width.maximum == 8) a else b)
        output := input
      }
    })
  }

  test("capture schema detects changed native width authority") {
    generate(new Component {
      val input = in UInt(8 bits)
      val output = out UInt(8 bits)
      val inspect = {
        val bias = input
        val schema = admit((a: UInt, b: UInt) => (a + b) ^ bias)
        input.setWidth(9)
        val error = intercept[IllegalArgumentException](schema.validateBindings())
        assert(error.getMessage.contains("CAPTURE-SCHEMA"))
        input.setWidth(8)
        schema.validateBindings()
        output := input
      }
    })
  }

  test("capture schema rejects a removed exact declaration and restores its original position") {
    generate(new Component {
      val input = in UInt(8 bits)
      val output = out UInt(8 bits)
      val inspect = {
        val bias = input
        val schema = admit((a: UInt, b: UInt) => (a + b) ^ bias)
        val scope = input.parentScope
        val preceding = input.lastScopeStatement
        input.removeStatementFromScope()
        val error = intercept[IllegalArgumentException](schema.validateBindings())
        assert(error.getMessage.contains("CAPTURE-SCHEMA"))
        if (preceding == null) scope.prepend(input) else preceding.insertNext(input)
        schema.validateBindings()
        output := input
      }
    })
  }
}

private[internals] object CertifiedReductionPureHelpers {
  def combine(a: UInt, b: UInt): UInt = mix(a + b, a)
  def mix(a: UInt, b: UInt): UInt = a ^ b
  def choose(condition: Bool, a: UInt, b: UInt): UInt = Mux(condition, a, b)
  def write(a: UInt, b: UInt): UInt = { a := b; a }
  def recursive(a: UInt, b: UInt): UInt = recursive(a, b)
  def effect(a: UInt, b: UInt): UInt = { CertifiedReductionHostState.calls += 1; a + b }
}

private[internals] object CertifiedReductionHostState { var calls = 0 }

private[internals] object CertifiedReductionClassVersionFixture {
  def operator: (UInt, UInt) => UInt = (a, b) => a + b
}

private[internals] object CertifiedReductionEffectfulInitializer {
  CertifiedReductionHostState.calls += 1
  def combine(a: UInt, b: UInt): UInt = a + b
}
