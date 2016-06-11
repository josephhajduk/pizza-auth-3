package moe.pizza.auth.adapters

import moe.pizza.auth.interfaces.PilotGrader
import moe.pizza.auth.models.Pilot
import moe.pizza.auth.models.Pilot.Status
import org.slf4j.LoggerFactory

/**
  * Created by Andi on 28/02/2016.
  */
class GraderChain(graders: Seq[PilotGrader]) extends PilotGrader {
  val log = LoggerFactory.getLogger(getClass)
  log.info(s"created GraderChain with ${graders.length} graders")

  override def grade(p: Pilot): Status.Value = {
    log.info("grading pilot with grader chain")
    graders.foldLeft(Status.unclassified) { (status, nextGrader) =>
      status match {
        case Status.unclassified => nextGrader.grade(p)
        case s => s
      }
    }
  }
}
