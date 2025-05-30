package workflows4s.doobie.postgres.testing

import workflows4s.doobie.ByteCodec

import java.io.*
import scala.util.Try

object JavaSerdeEventCodec {

  def get[T]: ByteCodec[T] = new ByteCodec[T] {
    override def read(bytes: IArray[Byte]): Try[T] = Try {
      val bais = new ByteArrayInputStream(IArray.genericWrapArray(bytes).toArray)
      val ois  = new ObjectInputStream(bais)
      val obj  = ois.readObject()
      ois.close()
      obj.asInstanceOf[T]
    }

    override def write(event: T): IArray[Byte] = {
      val baos = new ByteArrayOutputStream()
      val oos  = new ObjectOutputStream(baos)
      oos.writeObject(event)
      oos.close()
      IArray.unsafeFromArray(baos.toByteArray)
    }
  }
}
