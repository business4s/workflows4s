package workflows4s.mermaid

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed trait MermaidElement {
  def render: String
}

case class ClassDef(name: String, style: String) extends MermaidElement {
  def render: String = s"classDef $name $style"
}

case class Node(id: String, label: String, shape: Option[String] = None, clazz: Option[String] = None) extends MermaidElement {
  def render: String = {
    val classSuffix = clazz.map(c => s":::${c}").getOrElse("")
    shape match {
      case Some(s) => s"""$id$classSuffix@{ shape: $s, label: "$label"}"""
      case None    => s"""$id["$label"]$classSuffix"""
    }
  }
}

case class Link(from: String, to: String, label: Option[String] = None, prefix: "" | "x" = "", suffix: ">" | "x" = ">", midfix: "." | "" = "")
    extends MermaidElement {
  def render: String = label match {
    case Some(l) => s"""$from $prefix-$midfix-$suffix|"$l"| $to"""
    case None    => s"$from ${prefix}-${midfix}-${suffix} $to"
  }
}

case class Subgraph(id: String, name: String, elements: Seq[MermaidElement], clazz: Option[String] = None) extends MermaidElement {
  def render: String = {
    val classPrefix = clazz.map(c => s"$id:::${c}\n").getOrElse("")
    s"""${classPrefix}subgraph $id ["$name"]
       |${elements.map(_.render).mkString("\n")}
       |end""".stripMargin
  }
}

case class MermaidFlowchart(elements: Seq[MermaidElement]) {

  def addElement(element: MermaidElement): MermaidFlowchart  = MermaidFlowchart(elements :+ element)
  def addElements(el: Seq[MermaidElement]): MermaidFlowchart = MermaidFlowchart(elements ++ el)

  def render: String = {

    val usedClasses = elements.collect {
      case Node(_, _, _, Some(klass))               => klass
      case Subgraph(_, _, subElements, Some(klass)) =>
        klass +: subElements.collect { case Node(_, _, _, Some(subKlass)) => subKlass }
    }

    // Filter elements to include only used class definitions and other elements
    val filteredElements = elements.filter {
      case ClassDef(name, _) => usedClasses.contains(name)
      case _                 => true
    }

    s"""flowchart TD
       |${filteredElements.map(_.render).mkString("\n")}
       |""".stripMargin
  }

  def toViewUrl: String = {
    val encoded = URLEncoder.encode(render, StandardCharsets.UTF_8.toString)
    s"https://mermaid.live/edit#pako:${encoded}"
  }
}

object MermaidFlowchart {
  def apply(): MermaidFlowchart = MermaidFlowchart(Seq.empty)
}
