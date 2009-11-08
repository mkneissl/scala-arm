import java.net.{ServerSocket, Socket}
import java.io._
import scala.resource._

import org.junit._
import Assert._

class EchoServer {

  @volatile private[EchoServer] var running = true

  var thread : Option[Thread] = None

  def stop() : Unit = {
     running = false
     thread.foreach(_.join()) //TODO - Is this ok...
  }

  def start() : Unit = {
        val t = new Thread(runnable)
        t.start
        thread = Some(t)
  }

  val runnable = new Runnable {
    override def run() : Unit = {
      import resource._
      val server = new ServerSocket(8007);
      while(running) {
        for { connection <- ManagedResource(server.accept)
          outStream <- ManagedResource(new PrintWriter(new BufferedWriter(new OutputStreamWriter(connection.getOutputStream))))
          input <- ManagedResource(new BufferedReader(new InputStreamReader(connection.getInputStream)))
          line <- new JavaBufferedReaderLineIterator(input)
        } {
          println("Server returning: " + line)
          outStream.println(line)
          outStream.flush()
        }
      }
    }
  }
}

class EchoClient {
  def sendAndCheckString(arg : String) : Boolean = { 
    import resource._

    val result = for { connection <- ManagedResource(new Socket("localhost", 8007))
      out <- ManagedResource(new PrintWriter(new BufferedWriter(new OutputStreamWriter(connection.getOutputStream))))
      in <- ManagedResource(new BufferedReader(new InputStreamReader(connection.getInputStream)))
    } yield {         
      out.println(arg)
      out.flush()
      arg == in.readLine
    }
    result.map(x => x).opt.getOrElse(false)
  }
}


class TestSocketServer {
  @Test
  def testSocket() {
     val server = new EchoServer
     val client = new EchoClient
     server.start
     Thread.sleep(500)
     val result = client.sendAndCheckString("Hello, World!")
     server.stop()
     assertTrue("Socket Server Failed to respond correctly", result)
  }
}
