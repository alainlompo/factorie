/* Copyright (C) 2008-2010 Univ of Massachusetts Amherst, Computer Science Dept
   This file is part of "FACTORIE" (Factor graphs, Imperative, Extensible)
   http://factorie.cs.umass.edu, http://code.google.com/p/factorie/
   This software is provided under the terms of the Eclipse Public License 1.0
   as published by http://www.opensource.org.  For further information,
   see the file `LICENSE.txt' included with this distribution. */

package cc.factorie.optimize
import cc.factorie._
import scala.collection.mutable.IndexedSeq
import cc.factorie.la.ArrayLA.Implicits._

/** Maximize the Optimizable object by changing parameters only in the direction specified by 'line'.
    @author Andrew McCallum */
trait LineOptimizer {
  def optimize(line:Array[Double], initialStep:Double): Double
  def optimizable: Optimizable
}

/** Maximize the Optimizable object by changing parameters only in the direction specified by 'line',
    as specified in Numerical Recipes in C: p.385. "lnsrch". 
    It is a simple backtracking line search. No attempt at accurately finding the true minimum is
    made. The goal is only to ensure that BackTrackLineSearch will
    return a position of higher value.
    @author Andrew McCallum
 */
class BackTrackLineOptimizer(val optimizable:OptimizableByValueAndGradient, val maxIterations:Int = 100) extends LineOptimizer {
  var gradientNormMax = 100.0
  var relTolx = 1e-7
  var absTolx = 1e-4
  val ALF = 1e-4
  val EPS = 3.0e-12
  val stpmax = 100.0

  def optimize(line:Array[Double], initialStep:Double): Double = {
    //return 0.0
    var gradient = optimizable.getOptimizableGradient()
    var params = optimizable.getOptimizableParameters()
    var f = Math.NaN_DOUBLE
    var f2 = optimizable.optimizableValue
    var fold = f2

    // If gradient is too steep, bring it down to gradientNormMax
    var sum = line.twoNorm
    if (sum > gradientNormMax) gradient *= (gradientNormMax / sum)
    var slope = gradient dot line
    if (slope <= 0.0) throw new Error("Slope="+slope+" is negative or zero.")
    // Find maximum lambda; converge when (delta x) / x < REL_TOLX for all coordinates.
    // Largest step size that triggers this threshold is saved in alamin
    var test = 0.0; var temp = 0.0
    forIndex(gradient.length)(i => {
      temp = Math.abs(line(i)) / Math.max(Math.abs(params(i)),1.0)
      if (temp > test) test = temp
    })
    var alamin = relTolx / test
    var alam = 1.0; var oldAlam = 0.0; var tmplam = 0.0; var alam2 = 0.0
    for (iteration <- 0 until maxIterations) {
      assert (alam != oldAlam)
      params.incr(line, alam-oldAlam) // Move parameters in direction of line
      // Check for convergence
      val maxdiff = line.infinityNorm * (alam-oldAlam)
      if (alam < alamin || maxdiff < absTolx) return 0.0 // Convergence on change in params
      oldAlam = alam
      f = optimizable.optimizableValue
      // Check for sufficient function increase (Wolf condition)
      if (f >= fold+ALF*alam*slope) {
        if (f < fold) throw new Error("optimizableValue did not increase: old="+fold+" new="+f)
        return alam
      } else if (f == Math.POS_INF_DOUBLE || f2 == Math.POS_INF_DOUBLE) {
        // value is infinite; we have jumped into unstable territory.  Scale down jump
        tmplam = .2 * alam
        if (alam < alamin) return 0.0 // Exiting backtrack: jump to small; using previous parameters
      } else {
        // backtrack
        if (alam == 1.0) tmplam = -slope / (2.0*(f-fold-slope)) // first time through
        else {
          val rhs1 = f-fold-alam*slope
          val rhs2 = f2-fold-alam2*slope
          assert((alam-alam2) != 0, "FAILURE: dividing by alam-alam2.  alam="+alam)
          val a = (rhs1/(alam*alam)-rhs2/(alam2*alam2))/(alam-alam2)
	  val b = (-alam2*rhs1/(alam*alam)+alam*rhs2/(alam2*alam2))/(alam-alam2);
          if (a == 0.0) tmplam = -slope/(2.0*b)
          else {
            val disc = b*b-3.0*a*slope
            if (disc < 0.0) tmplam = .5*alam
            else if (b <= 0.0) tmplam = (-b+Math.sqrt(disc))/(3.0*a)
            else tmplam = -slope/(b+Math.sqrt(disc))
            if (tmplam > .5*alam) tmplam = .5*alam
          }
        }
        alam2 = alam
        f2 = f
        alam = Math.max(tmplam, .1*alam)
      }
    }
    throw new Error("Too many iterations.")
  }

}
