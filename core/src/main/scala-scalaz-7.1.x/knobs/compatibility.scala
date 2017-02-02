package knobs

import scalaz.\/
import scalaz.concurrent.Task

object compatibility {
  implicit class BedazledTask[A](task: Task[A]){ self =>
    def unsafePerformAsync(g: (Throwable \/ A) => Unit): Unit = task.runAsync(g)
    def unsafePerformSync: A = task.run
  }
}
