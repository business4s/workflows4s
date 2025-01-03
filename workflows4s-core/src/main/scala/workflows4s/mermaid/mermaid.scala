package workflows4s.mermaid

sealed trait MermaidElement {
  def render: String
}

case class Node(id: String, label: String, shape: Option[String] = None) extends MermaidElement {
  def render: String = shape match {
    case Some(s) => s"""$id@{ shape: $s, label: "$label"}"""
    case None    => s"""$id["$label"]"""
  }
}

case class Link(from: String, to: String, label: Option[String] = None, prefix: "" | "x" = "", suffix: ">" | "x" = ">", midfix: "." | "" = "")
    extends MermaidElement {
  def render: String = label match {
    case Some(l) => s"""$from $prefix-$midfix-$suffix|"$l"| $to"""
    case None    => s"$from ${prefix}-${midfix}-${suffix} $to"
  }
}

case class Subgraph(id: String, name: String, elements: Seq[MermaidElement]) extends MermaidElement {
  def render: String =
    s"""subgraph $id ["$name"]
       |${elements.map(_.render).mkString("\n")}
       |end""".stripMargin
}

case class MermaidFlowchart(elements: Seq[MermaidElement]) {

  def addElement(element: MermaidElement): MermaidFlowchart  = MermaidFlowchart(elements :+ element)
  def addElements(el: Seq[MermaidElement]): MermaidFlowchart = MermaidFlowchart(elements ++ el)

  def render: String =
    s"""flowchart TD
       |${elements.map(_.render).mkString("\n")}
       |""".stripMargin
}

object MermaidFlowchart {
  def apply(): MermaidFlowchart = MermaidFlowchart(Seq.empty)
}