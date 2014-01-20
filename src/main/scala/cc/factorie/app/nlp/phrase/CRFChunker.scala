package cc.factorie.app.nlp.phrase

import cc.factorie.app.nlp._

import java.io._
import cc.factorie.util.{HyperparameterMain, ClasspathURL, BinarySerializer}
import cc.factorie.variable._
import cc.factorie.optimize.Trainer
import cc.factorie.app.chain.ChainModel
import cc.factorie.app.chain.Observations._
import scala.io.Source
import cc.factorie.app.nlp.load._
import scala._
import cc.factorie.app.nlp.pos.PennPosTag
import com.sun.javaws.exceptions.InvalidArgumentException
import scala.tools.nsc.util.ScalaClassLoader.URLClassLoader

/**
 * User: cellier
 * Date: 10/7/13
 * Time: 2:49 PM
 */

class CRFChunker[L<:ChunkTag](chunkDomain: CategoricalDomain[String], newChunkLabel: (Token) => L, url: java.net.URL=null)(implicit m: Manifest[L]) extends DocumentAnnotator {
  def process(document: Document) = {
    document.sentences.foreach(s => {
      if (s.nonEmpty) {
        s.tokens.foreach(t => if (!t.attr.contains(m.runtimeClass)) t.attr += newChunkLabel(t))
        features(s)
        model.maximize(s.tokens.map(_.attr[L]))(null)
      }
    })
    document
  }
  def prereqAttrs = Seq(classOf[Token], classOf[Sentence])
  def postAttrs = Seq(m.runtimeClass)
  def tokenAnnotationString(token: Token) = { val label = token.attr[L]; if (label ne null) label.categoryValue else "(null)" }

  def serialize(stream: OutputStream) {
    import cc.factorie.util.CubbieConversions._
    val dstream = new DataOutputStream(stream)
    BinarySerializer.serialize(ChunkFeaturesDomain.dimensionDomain, dstream)
    BinarySerializer.serialize(model, dstream)
    dstream.close()
  }
  def deserialize(stream: InputStream) {
    import cc.factorie.util.CubbieConversions._
    val dstream = new DataInputStream(stream)
    BinarySerializer.deserialize(ChunkFeaturesDomain.dimensionDomain, dstream)
    BinarySerializer.deserialize(model, dstream)
    dstream.close()
  }

  def train(trainSentences:Seq[Sentence], testSentences:Seq[Sentence], lrate:Double = 0.1, decay:Double = 0.01, cutoff:Int = 2, doBootstrap:Boolean = true, useHingeLoss:Boolean = false, numIterations: Int = 5, l1Factor:Double = 0.000001, l2Factor:Double = 0.000001)(implicit random: scala.util.Random) {
    trainSentences.foreach(s=>features(s))
    println("Features for Training Generated")
    ChunkFeaturesDomain.freeze()
    testSentences.foreach(features)

    def evaluate() {
      (trainSentences ++ testSentences).foreach(s => model.maximize(s.tokens.map(_.attr[L]))(null))
      val segmentEvaluation = new cc.factorie.app.chain.SegmentEvaluation[L](chunkDomain.categories.filter(_.length > 2).map(_.substring(2)))
      for (sentence <- testSentences) segmentEvaluation += sentence.tokens.map(_.attr[L])
      println(segmentEvaluation)
      println("Train accuracy: "+ HammingObjective.accuracy(trainSentences.flatMap(s => s.tokens.map(_.attr[L]))))
      println("Test accuracy: "+ HammingObjective.accuracy(testSentences.flatMap(s => s.tokens.map(_.attr[L]))))
    }
    val examples = trainSentences.map(sentence => new model.ChainStructuredSVMExample(sentence.tokens.map(_.attr[L]))).toSeq
    val optimizer = new cc.factorie.optimize.AdaGradRDA(rate=lrate, l1=l1Factor/examples.length, l2=l2Factor/examples.length)
    Trainer.onlineTrain(model.parameters, examples, maxIterations=numIterations, optimizer=optimizer, evaluate=evaluate, useParallelTrainer = false)
  }

  object ChunkFeaturesDomain extends CategoricalVectorDomain[String]

  class ChunkFeatures(val token:Token) extends BinaryFeatureVectorVariable[String] { def domain = ChunkFeaturesDomain; override def skipNonCategories = true }

  val model = new ChainModel[ChunkTag, ChunkFeatures, Token](chunkDomain,
    ChunkFeaturesDomain,
    l => l.token.attr[ChunkFeatures],
    l => l.token,
    t => t.attr[L]){
    useObsMarkov = false
  }

  def features(sentence: Sentence): Unit = {
    import cc.factorie.app.strings.simplifyDigits
    val tokens = sentence.tokens.zipWithIndex
    for ((token,i) <- tokens) {
      if(token.attr[ChunkFeatures] ne null)
        token.attr.remove[ChunkFeatures]
      val features = token.attr += new ChunkFeatures(token)
      //def pastTag(offset:Int): String = { val t = token.next(offset); "P@"+offset+(if (t ne null) t.attr[BILOU2LayerChunkTag].target.aimer.categoryValue else null) }
      val rawWord = token.string
      val posTag = token.attr[PennPosTag]
      val word = simplifyDigits(rawWord).toLowerCase
      features += "SENTLOC="+i
      features += "P="+posTag
      //features += "Past="+pastTag(-2)
      //features += "W="+word
      features += "Raw="+rawWord
      val shape = cc.factorie.app.strings.stringShape(rawWord, 2)
      features += "WS="+shape // word conjoined with shape
      //if (word.length > 5) { features += "P="+cc.factorie.app.strings.prefix(word, 4); features += "S="+cc.factorie.app.strings.suffix(word, 4) }
      if (token.isPunctuation) features += "PUNCTUATION"
      //features += "STEM=" + cc.factorie.app.strings.porterStem(word)
      //features += "WSIZE=" + rawWord.length
      //val j = 3
      //features += "SUFFIX" + j + "=" + word.takeRight(j)
      //features += "PREFIX" + j + "=" + word.take(j)
      features += "BIAS"
    }
    addNeighboringFeatureConjunctions(sentence.tokens, (t: Token) => t.attr[ChunkFeatures], "W=[^@]*$", List(-2), List(-1), List(1),List(2), List(-1,0), List(0,1))
    addNeighboringFeatureConjunctions(sentence.tokens, (t: Token) => t.attr[ChunkFeatures], "P=[^@]*$", List(-2), List(-1), List(1), List(2), List(-2,-1), List(-1,0), List(0,1), List(1,2),List(-2,-1,0),List(-1,0,1),List(0,1,2))
  }
}

object BILOUCRFChunker extends CRFChunker[BILOUChunkTag](BILOUChunkDomain.dimensionDomain, (t) => new BILOUChunkTag(t,"O")) {
  deserialize(ClasspathURL[CRFChunker[BILOUChunkTag]](".factorie").openConnection().getInputStream)
}

object BIOCRFChunker extends CRFChunker[BIOChunkTag](BIOChunkDomain.dimensionDomain, (t) => new BIOChunkTag(t,"O")) {
  deserialize(ClasspathURL[CRFChunker[BIOChunkTag]](".factorie").openConnection().getInputStream)
}

object NestedCRFChunker extends CRFChunker[BILOU2LayerChunkTag](BILOU2LayerChunkDomain.dimensionDomain, (t) => new BILOU2LayerChunkTag(t,"O:O"))
{
  deserialize(new FileInputStream(new java.io.File("NestedCRFChunker.factorie")))
}

object CRFChunkingTrainer extends HyperparameterMain {
  def generateErrorOutput(sentences: Seq[Sentence]): String ={
    val sb = new StringBuffer
    sentences.map{s=> s.tokens.map{t=>sb.append("%s %20s %10s %10s  %s\n".format(if (t.attr[ChunkTag].valueIsTarget) " " else "*", t.string, t.attr[PennPosTag], t.attr[ChunkTag].target.categoryValue, t.attr[ChunkTag].categoryValue))}.mkString("\n")}.mkString("\n")
  }

  def evaluateParameters(args: Array[String]): Double = {
    implicit val random = new scala.util.Random(0)
    val opts = new ChunkerOpts
    opts.parse(args)
    assert(opts.trainFile.wasInvoked)
    val chunk = opts.trainingEncoding.value match {
      case "BILOU" => new CRFChunker[BILOUChunkTag](BILOUChunkDomain.dimensionDomain, (t) => new BILOUChunkTag(t,"O"))
      case "BIO" => new CRFChunker[BIOChunkTag](BIOChunkDomain.dimensionDomain, (t) => new BIOChunkTag(t,"O"))
      case "NESTED" => new CRFChunker[BILOU2LayerChunkTag](BILOU2LayerChunkDomain.dimensionDomain, (t) => new BILOU2LayerChunkTag(t,"O:O"))
    }


    val trainDocs = LoadConll2000.fromSource(Source.fromFile(opts.trainFile.value),opts.inputEncoding.value)
    val testDocs =  LoadConll2000.fromSource(Source.fromFile(opts.testFile.value),opts.inputEncoding.value)

    println("Read %d training tokens.".format(trainDocs.map(_.tokenCount).sum))
    println("Read %d testing tokens.".format(testDocs.map(_.tokenCount).sum))

    val trainPortionToTake = if(opts.trainPortion.wasInvoked) opts.trainPortion.value.toDouble  else 1.0
    val testPortionToTake =  if(opts.testPortion.wasInvoked) opts.testPortion.value.toDouble  else 1.0
    val trainSentencesFull = trainDocs.flatMap(_.sentences).filter(!_.isEmpty)
    val trainSentences = trainSentencesFull.take((trainPortionToTake*trainSentencesFull.length).floor.toInt)
    val testSentencesFull = testDocs.flatMap(_.sentences).filter(!_.isEmpty)
    val testSentences = testSentencesFull.take((testPortionToTake*testSentencesFull.length).floor.toInt)

    chunk.train(trainSentences, testSentences,
      opts.rate.value, opts.delta.value, opts.cutoff.value, opts.updateExamples.value, opts.useHingeLoss.value, l1Factor=opts.l1.value, l2Factor=opts.l2.value)
    if (opts.saveModel.value) {
      chunk.serialize(new FileOutputStream(new File(opts.modelFile.value)))
//      val chunk2 = new CRFChunker[BILOU2LayerChunkTag](BILOU2LayerChunkDomain.dimensionDomain, (t,s) => new BILOU2LayerChunkTag(t,s))
//      chunk2.deserialize(new FileInputStream(new java.io.File(opts.modelFile.value)))
//      val acc2 = HammingObjective.accuracy(testDocs.flatMap(d => d.sentences.flatMap(s => s.tokens.map(_.attr[BILOU2LayerChunkTag]))))
//      println(acc2)
    }
    val acc = HammingObjective.accuracy(testDocs.flatMap(d => d.sentences.flatMap(s => s.tokens.map(_.attr[BILOU2LayerChunkTag]))))
    if(opts.targetAccuracy.wasInvoked) assert(acc > opts.targetAccuracy.value.toDouble, "Did not reach accuracy requirement")
    val writer = new PrintWriter(new File("ErrorOutputNested2011.txt" ))
    writer.write(generateErrorOutput(testSentences))
    //testSentences.flatMap(_.tokens.map{t => if(!t.attr[BILOU2LayerChunkTag].valueIsTarget) writer.write("*\t"+ t.string + "\t" + t.attr[PennPosTag].categoryValue + "\t" + t.attr[BILOU2LayerChunkTag].target.categoryValue + "\t" + t.attr[BILOU2LayerChunkTag].categoryValue) else writer.write(" \t" + t.string + "\t" + t.attr[PennPosTag].categoryValue + "\t" + t.attr[BILOU2LayerChunkTag].target.categoryValue + "\t" + t.attr[BILOU2LayerChunkTag].categoryValue); writer.write("\n")})
    writer.close()
    acc
  }
}


object CRFChunkOptimizer {
  def main(args: Array[String]) {
    val opts = new ChunkerOpts
    opts.parse(args)
    opts.saveModel.setValue(false)
    val l1 = cc.factorie.util.HyperParameter(opts.l1, new cc.factorie.util.LogUniformDoubleSampler(1e-10, 1e2))
    val l2 = cc.factorie.util.HyperParameter(opts.l2, new cc.factorie.util.LogUniformDoubleSampler(1e-10, 1e2))
    val rate = cc.factorie.util.HyperParameter(opts.rate, new cc.factorie.util.LogUniformDoubleSampler(1e-4, 1e4))
    val delta = cc.factorie.util.HyperParameter(opts.delta, new cc.factorie.util.LogUniformDoubleSampler(1e-4, 1e4))
    val cutoff = cc.factorie.util.HyperParameter(opts.cutoff, new cc.factorie.util.SampleFromSeq(List(0,1,2,3)))
    val qs = new cc.factorie.util.QSubExecutor(60, "cc.factorie.app.nlp.chunk.CRFChunkingTrainer")
    val optimizer = new cc.factorie.util.HyperParameterSearcher(opts, Seq(l1, l2, rate, delta, cutoff), qs.execute, 200, 180, 60)
    val result = optimizer.optimize()
    println("Got results: " + result.mkString(" "))
    println("Best l1: " + opts.l1.value + " best l2: " + opts.l2.value)
    opts.saveModel.setValue(true)
    println("Running best configuration...")
    import scala.concurrent.duration._
    import scala.concurrent.Await
    Await.result(qs.execute(opts.values.flatMap(_.unParse).toArray), 5.hours)
    println("Done")
  }
}

class ChunkerOpts extends cc.factorie.util.DefaultCmdOptions with SharedNLPCmdOptions{
  val conllPath = new CmdOption("rcv1Path", "../../data/conll2000", "DIR", "Path to folder containing RCV1-v2 dataset.")
  val outputPath = new CmdOption("ouputPath", "../../data/conll2000/output.txt", "FILE", "Path to write output for evaluation.")
  val modelFile = new CmdOption("model", "", "FILENAME", "Filename for the model (saving a trained model or reading a running model.")
  val testFile = new CmdOption("test", "src/main/resources/test.txt", "FILENAME", "OWPL test file.")
  val trainFile = new CmdOption("train", "src/main/resources/train.txt", "FILENAME", "OWPL training file.")
  val l1 = new CmdOption("l1", 0.000001,"FLOAT","l1 regularization weight")
  val l2 = new CmdOption("l2", 0.00001,"FLOAT","l2 regularization weight")
  val rate = new CmdOption("rate", 10.0,"FLOAT","base learning rate")
  val delta = new CmdOption("delta", 100.0,"FLOAT","learning rate decay")
  val cutoff = new CmdOption("cutoff", 2, "INT", "Discard features less frequent than this before training.")
  val updateExamples = new  CmdOption("update-examples", true, "BOOL", "Whether to update examples in later iterations during training.")
  val useHingeLoss = new CmdOption("use-hinge-loss", false, "BOOL", "Whether to use hinge loss (or log loss) during training.")
  val saveModel = new CmdOption("save-model", false, "BOOL", "Whether to save the trained model.")
  val runText = new CmdOption("run", "", "FILENAME", "Plain text file on which to run.")
  val numIters = new CmdOption("num-iterations","5","INT","number of passes over the data for training")
  val inputEncoding = new CmdOption("input-encoding","BILOU","String","NESTED, BIO, BILOU - Encoding training file is in.")
  val trainingEncoding = new CmdOption("train-encoding", "BILOU","String","NESTED, BIO, BILOU - labels to use during training.")

}