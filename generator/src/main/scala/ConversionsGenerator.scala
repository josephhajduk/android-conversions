import java.io._
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.util.jar.{JarEntry, JarFile}

import org.objectweb.asm.signature.{SignatureReader, SignatureVisitor}
import org.objectweb.asm.tree.{ClassNode, FieldNode, MethodNode}
import org.objectweb.asm.{ClassReader, Opcodes, Type}

import scala.collection.JavaConverters._
import scala.language.postfixOps

case class ParamType(tpe: String, isArray: Boolean = false, tpeArgs: List[String] = List.empty)
object ParamType {
  val blank = ParamType(null, false)
}
class ConversionsGenerator extends ConversionsInterface {

  case class Interface(name: String, method: String, args: List[ParamType], ret: ParamType, placeholders: List[String])
  case class InterfaceUsage(method: String, intf: Interface)
  case class Usage (name: String, methods: List[InterfaceUsage])

  val TYPE_MAPPING = Map(
    "boolean" -> "Boolean",
    "byte"    -> "Byte",
    "char"    -> "Char",
    "int"     -> "Int",
    "short"   -> "Short",
    "long"    -> "Long",
    "float"   -> "Float",
    "double"  -> "Double",
    "void"    -> "Unit"
  )
  val ANNOTATION = Opcodes.ACC_ANNOTATION
  val PUBLIC_ABSTRACT = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT

  override def apply(srcManaged: File,
            classpath: java.util.List[File],
            androidJar: File,
            pkg: String,
            deps: java.util.List[File],
            deppkgs: java.util.List[String]): java.util.List[File] = {
    srcManaged.mkdirs()
    val conversions = new File(srcManaged, "conversions.scala")
    val extensions = new File(srcManaged, "extensions.scala")
    val urls = classpath.asScala.map(_.toURI.toURL).toArray
    val androidClasses = new URLClassLoader(urls, classOf[Int].getClassLoader)
    val publics = collectPublics(androidJar, androidClasses)
    val nesteds = collectNesteds(androidJar, androidClasses)
    val allintfs = deps.asScala.foldLeft(List.empty[Interface]) { (ac, x) =>
        ac ++ collectInterfaces(x, androidClasses, collectPublics(x, androidClasses), collectNesteds(x, androidClasses))
    }
    val interfaces = collectInterfaces(androidJar, androidClasses, publics, nesteds)
    val usages = collectUsages(androidJar, interfaces ++ allintfs)
    writeConversions(interfaces, pkg, conversions)
    writeExtensions(usages, pkg, extensions, deppkgs.asScala.toList)
    List(conversions, extensions).asJava
  }

  def collectPublics(androidJar: File, android: ClassLoader): Set[String] = {
    val input = new JarFile(androidJar)
    val r = input.entries.asScala.toList collect { case entry if entry.getName.endsWith(".class") =>
      val in = input.getInputStream(entry)
      val reader = new ClassReader(in)
      val classNode = new ClassNode(Opcodes.ASM5)
      reader.accept(classNode, 0)
      in.close()
      if (isPublic(classNode.access)) Some(classNode.name) else None
    } collect { case Some(x) => x }
    input.close()
    r.toSet
  }
  def collectNesteds(androidJar: File, android: ClassLoader): Set[String] = {
    val input = new JarFile(androidJar)
    val r = input.entries.asScala.toList collect { case entry if entry.getName.endsWith(".class") =>
      val in = input.getInputStream(entry)
      val reader = new ClassReader(in)
      val classNode = new ClassNode(Opcodes.ASM5)
      reader.accept(classNode, 0)
      in.close()
      if (isNestedClass(classNode)) Some(classNode.name.replace('/','.').replace('$','.')) else None
    } collect { case Some(x) => x }
    input.close()
    r.toSet
  }

  def collectInterfaces(androidJar: File, android: ClassLoader, publics: Set[String], nesteds: Set[String]): List[Interface] = {
    val input = new JarFile(androidJar)
    val r = input.entries.asScala.toList collect { case entry if entry.getName.endsWith(".class") =>
      val in = input.getInputStream(entry)
      val e = processForInterfaces(entry, in, android, publics, nesteds)
      in.close()
      e
    } collect { case Some(x) => x }
    input.close()
    r
  }

  def collectUsages(androidJar: File, intfs: List[Interface]): List[Usage] = {
    val ifacemap = intfs map (i => i.name -> i) toMap

    val input = new JarFile(androidJar)
    val r = input.entries.asScala.toList collect { case entry if entry.getName.endsWith(".class") =>
      val in = input.getInputStream(entry)
      val e = processForUsages(entry, in, ifacemap)
      in.close()
      e
    } collect { case Some(x) => x }
    input.close()
    r
  }

  def hasNoCtorOrNoArg(cls: ClassNode) = {
    val ctors = cls.methods.asScala collect { case m: MethodNode if m.name == "<init>" => m }
    ctors.isEmpty || (ctors exists { m => isPublic(m.access) && Type.getArgumentTypes(m.desc).isEmpty })
  }

  def stringifyPtype(p: ParamType) = {
    val t = fixupArgType(p.tpe)
    val tary = if (p.isArray) s"Array[$t]" else t
    if (p.tpeArgs.nonEmpty) s"$tary[${p.tpeArgs map fixupArgType mkString ","}]" else tary
  }
  def processForInterfaces(entry: JarEntry, in: InputStream, android: ClassLoader, publics: Set[String], nesteds: Set[String]): Option[Interface] = {
    val reader = new ClassReader(in)
    val classNode = new ClassNode(Opcodes.ASM5)
    reader.accept(classNode, 0)

    if (classNode.name.startsWith("android") &&
      !isNestedClass(classNode) &&
      !classNode.name.endsWith("NumberKeyListener") && // public getInputType + protected getAcceptedChars
      !classNode.name.endsWith("AsyncTask") && // multiple type params, not handled
      (classNode.name.indexOf('$') == -1 || publics(classNode.name.substring(0, classNode.name.indexOf('$')))) &&
      hasNoCtorOrNoArg(classNode) &&
      isAbstract(classNode.access)) {
      val candidateMethods = classNode.methods.asScala.collect {
        case m: MethodNode if (m.access & Opcodes.ACC_ABSTRACT) == Opcodes.ACC_ABSTRACT => m
      }

      if (candidateMethods.size == 1) {
        // having to fall back to reflection sucks, can't detect superclass abstracts otherwise
        val cls = android.loadClass(classNode.name.replace('/','.'))
        val count = cls.getMethods count { m =>
          Modifier.isAbstract(m.getModifiers)
        }

        if (count == 1) {
          val method = candidateMethods.head
          val params = Type.getArgumentTypes(method.desc)
          val params1 = params map (p => fixupArgType(p.getClassName)) map (ParamType.apply(_, false, List.empty)) toList

          if (params1 exists (p => p.tpe.contains("/internal/") || p.tpeArgs.exists(_ contains "/internal/"))) {
            None

          } else {
            val (sig, ret, ph) = if (method.signature != null) {
              val sr = SigReader(method.signature)
              (sr.params, sr.ret, sr.placeholders.toList)
            } else (params1, ParamType(fixupArgType(Type.getReturnType(method.desc).getClassName)), List.empty)

            val intf = Interface(classNode.name.replace('/', '.'), method.name, sig, ret, ph)
            if (nesteds(ret.tpe))
              None
            else
              Option(intf)
          }
        } else None
      } else
        None
    } else
      None
  }

  def isAbstract(ac: Int) = (ac & ANNOTATION) == 0 && (ac & PUBLIC_ABSTRACT) == PUBLIC_ABSTRACT
  def isPublic(ac: Int) = (ac & Opcodes.ACC_PUBLIC) == Opcodes.ACC_PUBLIC
  def isSynthetic(n: FieldNode) = (n.access & Opcodes.ACC_SYNTHETIC) == Opcodes.ACC_SYNTHETIC
  def isNestedClass(node: ClassNode) = node.name.indexOf('$') != -1 &&
    node.fields.asScala.map(_.asInstanceOf[FieldNode]).exists(isSynthetic)

  def processForUsages(entry: JarEntry, in: InputStream, intfs: Map[String, Interface]): Option[Usage] = {
    val ifacenames = intfs.keySet
    val reader = new ClassReader(in)
    val classNode = new ClassNode(Opcodes.ASM5)
    reader.accept(classNode, 0)

    if (isPublic(classNode.access) && !isNestedClass(classNode) && classNode.name.startsWith("android")) {
      val methods = classNode.methods.asScala collect { case m: MethodNode => m } filter { case method =>
        val params = Type.getArgumentTypes(method.desc)
        method.name.startsWith("set") && params.length == 1 && (params map (_.getClassName) exists ifacenames)
      }
      if (methods.nonEmpty) {
        val usages = methods.foldLeft(List.empty[InterfaceUsage]) { (ac, m) =>
          val intf = intfs(Type.getArgumentTypes(m.desc)(0).getClassName)
          InterfaceUsage(m.name, intf) :: ac
        }
        Option(Usage(classNode.name, usages))
      } else None
    } else
      None
  }

  def translateJavaPrimitive(s: String) = {
    TYPE_MAPPING.getOrElse(s, s)
  }

  def fixupArgType(s: String) = {
    val r = if (s.endsWith("[]")) {
      "Array[" + translateJavaPrimitive(s.dropRight(2)) + "]"
    } else translateJavaPrimitive(s)
    r.dropWhile(_ == '*').replace('$', '.').replace('/', '.').replaceAll("java.lang.Class$", "java.lang.Class[_]")
  }

  def decapitalize(s: String): String = {
    s(0).toLower + s.tail
  }
  def conversionName(iface: Interface, fn0: Boolean) = {
    val fn = "fn" + (if (fn0) "0" else iface.args.size)
    s"`$fn to ${iface.name}`"
  }

  def conversionWildcards(iface: Interface) = {
    val placeholders = if (iface.placeholders.isEmpty) "" else iface.placeholders mkString ","
    val wildcards = if (placeholders.nonEmpty) s"[$placeholders]" else placeholders
    if (iface.ret.tpe == "Unit") {
      val ph = if (placeholders.nonEmpty) "," + placeholders else placeholders
      s"[A$ph]"
    } else {
      wildcards
    }
  }

  def fnNSignature(iface: Interface): String = {
    val fixed = iface.args map (arg => fixupArgType(stringifyPtype(arg)))
    val argTypes = fixed mkString ", "
    val sig = if (iface.ret.tpe == "Unit") {
      "A"
    } else {
      stringifyPtype(iface.ret)
    }
    s"($argTypes) => $sig"
  }

  def fn0Signature(iface: Interface): String = {
    val sig = if (iface.ret.tpe == "Unit") {
      "A"
    } else {
      stringifyPtype(iface.ret)
    }
    s"() => $sig"
  }
  def byNameSignature(iface: Interface): String = {
    val sig = if (iface.ret.tpe == "Unit") {
      "A"
    } else {
      stringifyPtype(iface.ret)
    }
    s"=> $sig"
  }

  def usageToExtension(usage: Usage): String = {
    val cls = fixupArgType(usage.name)
    val clsname = cls.substring(cls.lastIndexOf(".") + 1)
    // yuck, don't know how to detect otherwise
    val wildcards = if (clsname == "AdapterView") "[_]" else ""
    val names = usage.methods.distinct map { m =>
      val name = if (m.method.startsWith("set")) {
        m.method.drop(3)
      } else m.method
      val name2 = if (name.endsWith("Listener")) {
        name.dropRight("Listener".length)
      } else name

      if (m.intf.args.isEmpty) {
        s"""
           |    @inline def ${decapitalize(name2)}${conversionWildcards(m.intf)}(fn: ${byNameSignature(m.intf)}) =
           |      base.${m.method}(${conversionName(m.intf, true)}(() => fn))
          """.stripMargin
      } else {
         s"""
           |    @inline def ${decapitalize(name2)}0${conversionWildcards(m.intf)}(fn: ${byNameSignature(m.intf)}) =
           |      base.${m.method}(${conversionName(m.intf, true)}(() => fn))
           |    @inline def ${decapitalize(name2)}${conversionWildcards(m.intf)}(fn: ${fnNSignature(m.intf)}) =
           |      base.${m.method}(${conversionName(m.intf, false)}(fn))
          """.stripMargin
      }
    } mkString "\n"
    s"""
       |  implicit class ExtensionOf$clsname(val base: $cls$wildcards) extends AnyVal {
       |    $names
       |    def asScala = this
       |  }
    """.stripMargin
  }

  def interfaceToConversion(iface: Interface): String = {
    val placeholders = if (iface.placeholders.isEmpty) "" else iface.placeholders mkString ","
    val wildcards = if (placeholders.nonEmpty) s"[$placeholders]" else placeholders
    val cls = fixupArgType(iface.name)
    val fixed = iface.args map (arg => fixupArgType(stringifyPtype(arg)))
    val argNames = fixed zip ('a' to 'z')
    val fargs = argNames map { case (_, n) => n } mkString ", "
    val args = argNames map { case (t, n) => s"$n: $t" } mkString ", "

    val s1 = s"""
      |  @inline implicit def ${conversionName(iface, false)}${conversionWildcards(iface)}(fn: ${fnNSignature(iface)}): $cls$wildcards = new $cls$wildcards {
      |    override def ${iface.method}($args) = fn($fargs)
      |  }
      |
    """.stripMargin
    val s2 =
      s"""
      |  @inline implicit def ${conversionName(iface, true)}${conversionWildcards(iface)}(fn: ${fn0Signature(iface)}): $cls$wildcards = new $cls$wildcards {
      |    override def ${iface.method}($args) = fn()
      |  }
    """.stripMargin
    if (iface.args.isEmpty) s1 else s1 + s2
  }

  def writeConversions(intfs: List[Interface], pkg: String, output: File): Unit = {
    val fout = new PrintWriter(new OutputStreamWriter(new FileOutputStream(output), "utf-8"))
    fout.println(s"package $pkg")
    fout.println("import language.implicitConversions")
    fout.println("package object conversions {")
    intfs foreach { iface =>
      fout.println(interfaceToConversion(iface))
    }
    fout.println("}")
    fout.close()
  }
  def writeExtensions(usages: List[Usage], pkg: String, output: File, deppkgs: List[String]): Unit = {
    val fout = new PrintWriter(new OutputStreamWriter(new FileOutputStream(output), "utf-8"))
    fout.println(s"package $pkg")
    deppkgs.foreach { d =>
      fout.println(s"import $d.conversions._")
    }
    fout.println("import conversions._")
    fout.println("package object extensions {")
    usages foreach { usage =>
      fout.println(usageToExtension(usage))
    }
    fout.println("}")
    fout.close()
  }

  case class SigReader(signature: String) extends SignatureVisitor(Opcodes.ASM5) {
    var params = List.empty[ParamType]
    var ret = ParamType.blank
    var placeholders = Set.empty[String]
    private var ptype = ParamType.blank
    private var isArray = false
    private var isParams = false
    new SignatureReader(signature).accept(this)

    override def visitParameterType() = {
      if (ptype != ParamType.blank) params = params :+ ptype
      ptype = ParamType.blank
      isArray = false
      isParams = true
      this
    }
    override def visitReturnType() = {
      if (ptype != ParamType.blank) {
        if (ptype.tpe == null) {
          throw new IllegalStateException("tpe cannot be null: " + signature)
        }
        params = params :+ ptype
      }
      isParams = false
      this
    }

    override def visitArrayType() = {
      isArray = true
      if (isParams) ptype = ptype.copy(isArray = true)
      else ret = ret.copy(isArray = true)
      this
    }
    override def visitTypeArgument() = {
      ptype = ptype.copy(tpeArgs = ptype.tpeArgs :+ "_")
    }

    override def visitTypeArgument(wildcard: Char) = this
    override def visitClassType(name: String) = if (isParams) {
      if (ptype.tpe == null) ptype = ptype.copy(tpe = name) else {
        ptype = ptype.copy(tpeArgs = ptype.tpeArgs :+ name)
      }
    } else {
      if (ret.tpe == null) ret = ret.copy(tpe = name) else {
        ret = ret.copy(tpeArgs = ret.tpeArgs :+ name)
      }
    }
    override def visitInnerClassType(name: String) = visitClassType(name)

    override def visitBaseType(descriptor: Char) = {
      val name = fixupArgType(Type.getType(descriptor.toString).getClassName)
      if (isParams) {
        if (ptype.tpe == null) ptype = ptype.copy(tpe = name)
        else {
          ptype = ptype.copy(tpeArgs = ptype.tpeArgs :+ name)
        }
      } else {
        if (ret.tpe == null) ret = ret.copy(tpe = name)
        else ret = ret.copy(tpeArgs = ret.tpeArgs :+ name)
      }
    }
    override def visitTypeVariable(name: String) = {
      placeholders += name
      val n = "*" + name
      if (isParams) {
        if (ptype.tpe == null) ptype = ptype.copy(tpe = n) else {
          ptype = ptype.copy(
            tpeArgs = ptype.tpeArgs :+ n)
        }
      } else {
        if (ret.tpe == null)
          ret = ret.copy(tpe = n) else {
          ret = ret.copy(
            tpeArgs = ret.tpeArgs :+ n)
        }
      }
    }
  }
}
