package workflow4s.wio.model

import scala.reflect.ClassTag

object ModelUtils {

  def prettifyName(name: String): String = name.capitalize.replaceAll("([a-z])([A-Z])", "$1 $2")

  def getPrettyNameForClass(ct: ClassTag[_]): String = {
//    ct.runtimeClass.getName.stripPrefix(ct.runtimeClass.getPackageName+".")
//      .split('$').filter(_.nonEmpty).map(prettifyName).mkString(" / ")
    prettifyName(ct.runtimeClass.getSimpleName)
  }

  def getError(errorCt: ClassTag[_]): Option[WIOModel.Error] =
    if(errorCt != implicitly[ClassTag[Nothing]]) Some(WIOModel.Error(getPrettyNameForClass(errorCt)))
    else None

}
