/*
 * Copyright 2016 Azavea
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package geotrellis.pointcloud.spark.triangulation

/** Mutable, non-thread safe class to keep track of half edges
  * during Delaunay triangulation.
  *
  * Edge table contains an Array[Int] of triples:
  *       [i,e1,e2] where i is the vertex of the halfedge (where the half edge 'points' to)
  *                       e1 is the halfedge index of the flip of the halfedge
  *                          (the halfedge that points to the source)
  *                       e2 is the halfedge index of the next half edge
  *                          (counter-clockwise of the triangle)
  */
class HalfEdgeTable(_size: Int) {
  private var size: Int = _size
  private var idx = 0
  private var edgeCount = 0

  // This array will hold the packed arrays of the halfedge table.
  private var table = Array.ofDim[Int](size * 3)

  // Since the underlying implementation uses an array we can only
  // store so many unique values. 1<<30 is the largest power of two
  // that can be allocated as an array. since each halfedge
  // takes up three slots in our array, the maximum number of those we
  // can store is Int.MaxValue / 3. so that is the maximum size we can allocate
  // for our table.
  private final val MAXSIZE: Int = Int.MaxValue / 3

  // Once our buckets get 80% full, we need to resize
  private final val FACTOR: Double = 0.8

  private var limit = (size * FACTOR).toInt

  /** Create an edge pointing to single vertex.
    * This edge will have no flip or next set.
    */
  def createHalfEdge(v: Int): Int = {
    val e = edgeCount
    table(idx) = v
    idx += 3
    edgeCount += 1
    if (edgeCount > limit) resize()
    e
  }

  /** Create an edge for a single vertex,
    * with the specified flip and next set.
    */
  def createHalfEdge(v: Int, flip: Int, next: Int): Int = {
    val e = edgeCount
    table(idx) = v
    table(idx + 1) = flip
    table(idx + 2) = next
    idx += 3
    edgeCount += 1
    if (edgeCount > limit) { resize() }
    e
  }

  /** Create two half edges that
    * both flip and point to each other
    *
    * @return the half edge point at v2
    */
  def createHalfEdges(v1: Int, v2: Int): Int = {
    val e1 = createHalfEdge(v1)
    val e2 = createHalfEdge(v2)

    setFlip(e2, e1)
    setNext(e2, e1)

    setFlip(e1, e2)
    setNext(e1, e2)
    e2
  }

  /** Create three half edges that
    * flip and point to each other
    * in a triangle.
    *
    * @return   The outer halfedge pointing at v1
    *
    * @note It's up to the caller to set these edges in counter clockwise order
    */
  def createHalfEdges(v1: Int, v2: Int, v3: Int): Int = {
    //println(s"DELAUNAY2 - HE $v1 $v2 $v3")
    val inner1 = createHalfEdge(v1)
    val inner2 = createHalfEdge(v2)
    val inner3 = createHalfEdge(v3)

    val outer1 = createHalfEdge(v1)
    val outer2 = createHalfEdge(v2)
    val outer3 = createHalfEdge(v3)

    setNext(inner1, inner2)
    setNext(inner2, inner3)
    setNext(inner3, inner1)

    setFlip(inner1, outer3)
    setFlip(inner2, outer1)
    setFlip(inner3, outer2)

    setNext(outer1, outer3)
    setNext(outer3, outer2)
    setNext(outer2, outer1)

    setFlip(outer1, inner2)
    setFlip(outer2, inner3)
    setFlip(outer3, inner1)

    outer1
  }

  // TODO: Make this match the implementation of join() in HalfEdge.scala
  def joinTriangles(e1: Int, e2: Int): Unit = {
    // flip.prev.next = that.flip.next
    setNext(getPrev(getFlip(e1)), getNext(getFlip(e2)))

    // that.flip.prev.next = flip.next
    setNext(getPrev(getFlip(e2)), getNext(getFlip(e1)))

    //flip = that
    setFlip(e1, e2)

    //that.flip = this
    setFlip(e2, e1)
  }

  /** Sets a half edge's flip half edge
    */
  def setFlip(e: Int, flip: Int): Unit =
    table(e * 3 + 1) = flip

  /** Sets a half edge's next half edge
    */
  def setNext(e: Int, next: Int): Unit =
    table(e * 3 + 2) = next

  def getFlip(e: Int): Int =
    table(e * 3 + 1)

  def getNext(e: Int): Int =
    table(e * 3 + 2)

  def getPrev(e: Int): Int = {
    var p = getNext(getFlip(e))
    while (getNext(getFlip(p)) != e) {
      p = getNext(getFlip(p))
    }
    getFlip(p)
  }

  /** Returns the vertex index for this half edge
    * (the one in which it points to)
    */
  def getVert(e: Int): Int =
    table(e * 3)

  /** Sets the vertex index for this half edge
      * (the one in which it points to)
      */
    def setVert(e: Int, v: Int): Unit =
      table(e * 3) = v

  /** Returns the source vertex index for this half edge
    * (the one in which it points from)
    */
  def getSrc(e: Int): Int = {
    val f = getFlip(e)
    table(f * 3)
  }

  /** Sets the source vertex index for this half edge
    * (the one in which it points from)
    */
  def setSrc(e: Int, v: Int): Unit =
    table(getFlip(e) * 3) = v

  /** Returns the halfedge index of the halfedge you get
    * from rotating this halfedge clockwise from it's source
    * along the triangulation
    */
  def rotCWSrc(e: Int): Int =
    getNext(getFlip(e))

  /** Returns the halfedge index of the halfedge you get
    * from rotating this halfedge counter-clockwise from it's source
    * along the triangulation
    */
  def rotCCWSrc(e: Int): Int =
    getFlip(getPrev(e))

  /** Returns the halfedge index of the halfedge you get
    * from rotating this halfedge clockwise from it's destination
    * along the triangulation
    */
  def rotCWDest(e: Int): Int =
    getFlip(getNext(e))

  /** Returns the halfedge index of the halfedge you get
    * from rotating this halfedge counter-clockwise from it's destination
    * along the triangulation
    */
  def rotCCWDest(e: Int): Int =
    getPrev(getFlip(e))

  private def resize() {
    // It's important that size always be a power of 2. We grow our
    // hash table by x4 until it starts getting big, at which point we
    // only grow by x2.
    val factor = if (size < 10000) 4 else 2

    val nextSize = size * factor
    val nextTable = Array.ofDim[Int](nextSize * 3)

    println(s"RESIZE $size TO $nextSize")

    // Given the underlying array implementation we can only store so
    // many unique values. given that 1<<30
    if (nextSize > MAXSIZE) sys.error("edge table has exceeded max capacity")

    var i = 0
    while (i < idx) {
      nextTable(i) = table(i)
      i += 1
    }

    size = nextSize
    table = nextTable
    limit = (size * FACTOR).toInt
  }

  // Debug methods

  def showBoundingLoop(base: Int): Unit = {
    var e = base
    var l: List[Int] = Nil

    while (!l.contains(e)) {
      l = l :+ e
      print(s"|${getSrc(e)} -> ${getVert(e)}| ")
      e = getNext(e)
    }
    println("")
  }
}