package com.adrianfilip.multilane

import com.adrianfilip.multilane.Lane.CustomLane
import com.adrianfilip.multilane.MultiLaneLockerSpecProgs._
import zio.clock.Clock
import zio.duration._
import zio.random.Random
import zio.test.Assertion.equalTo
import zio.test.environment.{ Live, TestClock }
import zio.test._
import zio.{ Queue, Task, ZIO }

object MultiLaneLockerSpec
    extends DefaultRunnableSpec(
      suite("MultiLaneSequencerSpec")(
        testM("5 requests sameLane with 100 millis max prog delay -  using Live environment") {
          val maxDelay = 100.milliseconds
          val programs
            : (Map[Lane, Queue[Int]], Map[Lane, Queue[Int]], Set[Lane]) => List[(Set[Lane], ZIO[Clock, Any, Any])] =
            (requestsMap, responsesMap, lanes) =>
              (1 to 5).map(idx => generateProgram(requestsMap, responsesMap, lanes, idx, maxDelay)).toList

          Live.live {
            for {
              requestsMap  <- generateMap(Set(Lane.LANE1))
              responsesMap <- generateMap(Set(Lane.LANE1))
              mls          <- MultiLaneLocker.make
              _ <- ZIO.collectAllPar(
                    programs(requestsMap, responsesMap, Set(Lane.LANE1))
                      .map(x => mls.run(x._1, x._2))
                  )
              mappedRequests  <- extractResults(requestsMap)
              mappedResponses <- extractResults(responsesMap)
            } yield assert(mappedRequests, equalTo(mappedResponses)) && assert(mappedRequests.isEmpty, equalTo(false))

          }

        },
        testM("5 requests sameLane with 100 millis max prog delay - using TestClock adjust") {
          val maxDelay = 100.milliseconds
          val programs
            : (Map[Lane, Queue[Int]], Map[Lane, Queue[Int]], Set[Lane]) => List[(Set[Lane], ZIO[Clock, Any, Any])] =
            (requestsMap, responsesMap, lanes) =>
              (1 to 5).map(idx => generateProgram(requestsMap, responsesMap, lanes, idx, maxDelay)).toList

          for {
            requestsMap  <- generateMap(Set(Lane.LANE1))
            responsesMap <- generateMap(Set(Lane.LANE1))
            mls          <- MultiLaneLocker.make
            _            <- TestClock.adjust(10.seconds)
            _ <- ZIO.collectAllPar(
                  programs(requestsMap, responsesMap, Set(Lane.LANE1))
                    .map(x => mls.run(x._1, x._2))
                )
            mappedRequests  <- extractResults(requestsMap)
            mappedResponses <- extractResults(responsesMap)
          } yield assert(mappedRequests, equalTo(mappedResponses)) && assert(mappedRequests.isEmpty, equalTo(false))

        },
        testM("100 requests - random max 5 lanes and random max 10 millis program delay") {
          val lanesLineup   = Set("A", "B", "C", "D", "E").toList.map(CustomLane)
          val maxDelay      = 10.milliseconds
          val totalRequests = 100
          val maxLanes      = 5

          def buildRequests(requestsMap: Map[Lane, Queue[Int]], responsesMap: Map[Lane, Queue[Int]]) =
            (1 to totalRequests).map { idx =>
              for {
                lanesNr <- Random.Live.random.nextInt(maxLanes + 1).map(v => if (v == 0) 1 else v)
                lanes   <- ZIO.collectAll((1 to lanesNr).map(_ => getRandomLane(lanesLineup)))
              } yield generateProgram(requestsMap, responsesMap, lanes.toSet, idx, maxDelay)
            }

          Live.live {
            for {
              requestsMap  <- generateMap(lanesLineup.toSet)
              responsesMap <- generateMap(lanesLineup.toSet)
              mls          <- MultiLaneLocker.make
              requests     <- ZIO.collectAll(buildRequests(requestsMap, responsesMap))
              _ <- ZIO.collectAllPar(
                    requests
                      .map(x => mls.run(x._1, x._2))
                  )
              mappedRequests  <- extractResults(requestsMap)
              mappedResponses <- extractResults(responsesMap)
            } yield assert(mappedRequests, equalTo(mappedResponses)) && assert(mappedRequests.isEmpty, equalTo(false))
          }
        } @@ TestAspect.nonFlaky(10)
      )
    )

object MultiLaneLockerSpecProgs {

  private def randomSleep(topMillis: Duration) =
    Random.Live.random.nextInt(topMillis.toMillis.toInt).flatMap(t => ZIO.sleep(t.milliseconds))

  def getRandomLane(lanes: List[Lane]): Task[Lane] =
    for {
      idx <- Random.Live.random.nextInt(lanes.size)
    } yield lanes(idx)

  /**
   * When the program starts it will add it's ID to the requestsMap to all the lanes needed by the program.
   * When the program ends it will add it's ID to the responsesMap to all the lanes needed by the program.
   * Doing this will provide an easy way to check that for each lane the programs were executed in order,
   * because if for each lane the values and their orders are the same then it means that for each lane the programs
   * ran sequentially.
   */
  def generateProgram(
    requestsMap: Map[Lane, Queue[Int]],
    responsesMap: Map[Lane, Queue[Int]],
    lanes: Set[Lane],
    id: Int,
    maxDelay: Duration
  ): (Set[Lane], ZIO[Clock, Any, Any]) =
    (lanes, markLanes(id, lanes, requestsMap) *> randomSleep(maxDelay) *> markLanes(id, lanes, responsesMap))

  private def markLanes(id: Int, lanes: Set[Lane], progressMap: Map[Lane, Queue[Int]]) =
    ZIO.collectAll(lanes.map(l => progressMap.get(l).map(q => q.offer(id)).getOrElse(ZIO.fail("Missing lane"))))

  def generateMap(lanes: Set[Lane]): Task[Map[Lane, Queue[Int]]] =
    for {
      queues <- ZIO.collectAll(lanes.map(l => Queue.unbounded[Int].map(q => l -> q)))
    } yield queues.toMap

  def extractResults(map: Map[Lane, Queue[Int]]): Task[Map[Lane, List[Int]]] =
    ZIO
      .collectAll(map.map {
        case (l, q) => q.takeAll.map(ls => l -> ls)
      })
      .map(ls => ls.toMap)

}
