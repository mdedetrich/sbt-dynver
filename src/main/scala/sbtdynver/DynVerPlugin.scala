package sbtdynver

import java.util._

import scala.{ PartialFunction => ?=> }
import scala.util._

import sbt._
import sbt.Keys._

import scala.sys.process.{ Process, ProcessLogger }

object DynVerPlugin extends AutoPlugin {
  override def requires = plugins.JvmPlugin
  override def trigger  = allRequirements

  object autoImport {
    val dynver                         = taskKey[String]("The version of your project, from git")
    val dynverInstance                 = settingKey[DynVer]("The dynver instance for this build")
    val dynverCurrentDate              = settingKey[Date]("The current date, for dynver purposes")
    val dynverGitDescribeOutput        = settingKey[Option[GitDescribeOutput]]("The output from git describe")
    val dynverSonatypeSnapshots        = settingKey[Boolean]("Whether to append -SNAPSHOT to snapshot versions")
    val dynverGitPreviousStableVersion = settingKey[Option[GitDescribeOutput]]("The last stable tag")
    val dynverSeparator                = settingKey[String]("The separator to use between tag and distance, and the hash and dirty timestamp")
    val dynverCheckVersion             = taskKey[Boolean]("Checks if version and dynver match")
    val dynverAssertVersion            = taskKey[Unit]("Asserts if version and dynver match")
    val dynverAssertTagVersion         = taskKey[Unit]("Asserts if the version derives from git tags")

    // Would be nice if this were an 'upstream' key
    val isVersionStable         = settingKey[Boolean]("The version string identifies a specific point in version control, so artifacts built from this version can be safely cached")
    val previousStableVersion   = settingKey[Option[String]]("The last stable version as seen from the current commit (does not include the current commit's version/tag)")
  }
  import autoImport._

  override def buildSettings = Seq(
    version := {
      val out = dynverGitDescribeOutput.value
      val date = dynverCurrentDate.value
      val separator = dynverSeparator.value
      if (dynverSonatypeSnapshots.value) out.sonatypeVersion(date, separator)
      else out.version(date, separator)
    },
    isSnapshot              := dynverGitDescribeOutput.value.isSnapshot,
    isVersionStable         := dynverGitDescribeOutput.value.isVersionStable,
    previousStableVersion   := dynverGitPreviousStableVersion.value.previousVersion,

    dynverCurrentDate              := new Date,
    dynverInstance                 := DynVer(Some((baseDirectory in ThisBuild).value), dynverSeparator.value),
    dynverGitDescribeOutput        := dynverInstance.value.getGitDescribeOutput(dynverCurrentDate.value),
    dynverSonatypeSnapshots        := false,
    dynverGitPreviousStableVersion := dynverInstance.value.getGitPreviousStableTag,
    dynverSeparator                := DynVer.separator,

    dynver                  := {
      val dynver = dynverInstance.value
      if (dynverSonatypeSnapshots.value) dynver.sonatypeVersion(new Date)
      else dynver.version(new Date)
    },
    dynverCheckVersion      := (dynver.value == version.value),
    dynverAssertVersion     := {
      val v = version.value
      val dv = dynver.value
      if (!dynverCheckVersion.value)
        sys.error(s"Version and dynver mismatch - version: $v, dynver: $dv")
    },
    dynverAssertTagVersion  := {
      val v = version.value
      if (dynverGitDescribeOutput.value.hasNoTags)
        throw new MessageOnlyException(
          s"Failed to derive version from git tags. Maybe run `git fetch --unshallow`? Version: $v"
        )
    }
  )
}

final case class GitRef(value: String)
final case class GitCommitSuffix(distance: Int, sha: String)
final case class GitDirtySuffix(suffix: String) {
  def value: String = if (suffix.isEmpty) "" else s"+$suffix"
}

object GitRef extends (String => GitRef) {
  final implicit class GitRefOps(val x: GitRef) extends AnyVal { import x._
    def isTag: Boolean = value startsWith "v"
    def dropV: GitRef = GitRef(value.replaceAll("^v", ""))
    def mkString(prefix: String, suffix: String): String = if (value.isEmpty) "" else prefix + value + suffix
  }
}

object GitCommitSuffix extends ((Int, String) => GitCommitSuffix) {
  final implicit class GitCommitSuffixOps(val x: GitCommitSuffix) extends AnyVal { import x._
    def isEmpty: Boolean = distance <= 0 || sha.isEmpty
    def mkString(prefix: String, infix: String, suffix: String): String =
      if (sha.isEmpty) "" else prefix + distance + infix + sha + suffix
  }
}

object GitDirtySuffix extends (String => GitDirtySuffix) {
  final implicit class GitDirtySuffixOps(val x: GitDirtySuffix) extends AnyVal {
    def dropPlus: GitDirtySuffix = x
    def mkString(prefix: String, suffix: String): String = if (x.suffix.isEmpty) "" else prefix + x.suffix + suffix
    def asSuffix(separator: String): String = mkString(separator, "")
  }
}

final case class GitDescribeOutput(ref: GitRef, commitSuffix: GitCommitSuffix, dirtySuffix: GitDirtySuffix) {
  def version(separator: String): String = {
    val ds = dirtySuffix.asSuffix(separator)
    if (isCleanAfterTag) ref.dropV.value + ds // no commit info if clean after tag
    else if (commitSuffix.sha.nonEmpty) ref.dropV.value + separator + commitSuffix.distance + "-" + commitSuffix.sha + ds
    else commitSuffix.distance + "-" + ref.value + ds
  }
  def sonatypeVersion(separator: String): String =
    if (isSnapshot) version(separator) + "-SNAPSHOT" else version(separator)

  def version: String            = version(DynVer.separator)
  def sonatypeVersion: String    = sonatypeVersion(DynVer.separator)

  def isSnapshot(): Boolean      = hasNoTags() || !commitSuffix.isEmpty || isDirty()
  def previousVersion: String    = ref.dropV.value
  def isVersionStable(): Boolean = !isDirty()

  def hasNoTags(): Boolean       = !ref.isTag
  def isDirty(): Boolean         = dirtySuffix.suffix.nonEmpty
  def isCleanAfterTag: Boolean   = ref.isTag && commitSuffix.isEmpty && !isDirty()
}

object GitDescribeOutput extends ((GitRef, GitCommitSuffix, GitDirtySuffix) => GitDescribeOutput) {
  private val OptWs        =  """[\s\n]*""" // doesn't \s include \n? why can't this call .r?
  private val Tag          =  """(v[0-9][^+]*?)""".r
  private val Distance     =  """\+([0-9]+)""".r
  private val Sha          =  """([0-9a-f]{8})""".r
  private val HEAD         =  """HEAD""".r
  private val CommitSuffix = s"""($Distance-$Sha)""".r
  private val TstampSuffix =  """(?:\+([0-9]{8}-[0-9]{4}))""".r

  private val FromTag  = s"""^$OptWs$Tag$CommitSuffix?$TstampSuffix?$OptWs$$""".r
  private val FromSha  = s"""^$OptWs$Sha$TstampSuffix?$OptWs$$""".r
  private val FromHead = s"""^$OptWs$HEAD$TstampSuffix$OptWs$$""".r

  private[sbtdynver] def parse: String ?=> GitDescribeOutput = {
    case FromTag(tag, _, dist, sha, dirty) => parse0(   tag, dist, sha, dirty)
    case FromSha(sha, dirty)               => parse0(   sha,  "0",  "", dirty)
    case FromHead(dirty)                   => parse0("HEAD",  "0",  "", dirty)
  }

  private def parse0(ref: String, dist: String, sha: String, dirty: String) = {
    val commit = if (dist == null || sha == null) GitCommitSuffix(0, "") else GitCommitSuffix(dist.toInt, sha)
    GitDescribeOutput(GitRef(ref), commit, GitDirtySuffix(if (dirty eq null) "" else dirty))
  }

  implicit class OptGitDescribeOutputOps(val _x: Option[GitDescribeOutput]) extends AnyVal {
    def mkVersion(f: GitDescribeOutput => String, fallback: => String): String = _x.fold(fallback)(f)

    def version(d: Date, separator: String): String =
      mkVersion(_.version(separator), DynVer(None, separator) fallback d)
    def sonatypeVersion(d: Date, separator: String): String  =
      mkVersion(_.sonatypeVersion(separator), DynVer(None, separator) fallback d)

    def version(d: Date): String          = version(d, DynVer.separator)
    def sonatypeVersion(d: Date): String  = sonatypeVersion(d, DynVer.separator)
    def previousVersion: Option[String]   = _x.map(_.previousVersion)
    def isSnapshot: Boolean               = _x.forall(_.isSnapshot)
    def isVersionStable: Boolean          = _x.exists(_.isVersionStable)

    def isDirty: Boolean         = _x.fold(true)(_.isDirty())
    def hasNoTags: Boolean       = _x.fold(true)(_.hasNoTags())
  }

  private[sbtdynver] def timestamp(d: Date): String = "%1$tY%1$tm%1$td-%1$tH%1$tM" format d
  private[sbtdynver] def fallback(separator: String, d: Date) = s"HEAD$separator${timestamp(d)}"
}

// sealed just so the companion object can extend it. Shouldn't've been a case class.
sealed case class DynVer(wd: Option[File], separator: String) {
  def version(d: Date): String            = getGitDescribeOutput(d).version(d, separator)
  def sonatypeVersion(d: Date): String    = getGitDescribeOutput(d).sonatypeVersion(d, separator)
  def previousVersion : Option[String]    = getGitPreviousStableTag.previousVersion
  def isSnapshot(): Boolean               = getGitDescribeOutput(new Date).isSnapshot
  def isVersionStable(): Boolean          = getGitDescribeOutput(new Date).isVersionStable

  def makeDynVer(d: Date): Option[String] = getGitDescribeOutput(d) map (_.version(separator))
  def isDirty(): Boolean                  = getGitDescribeOutput(new Date).isDirty
  def hasNoTags(): Boolean                = getGitDescribeOutput(new Date).hasNoTags

  def getDistanceToFirstCommit(): Option[Int] = {
    val process = Process(s"git rev-list --count HEAD", wd)
    Try(process !! impl.NoProcessLogger).toOption
      .map(_.trim.toInt)
  }

  def getGitDescribeOutput(d: Date): Option[GitDescribeOutput] = {
    val process = Process(s"git describe --long --tags --abbrev=8 --match v[0-9]* --always --dirty=+${timestamp(d)}", wd)
    Try(process !! impl.NoProcessLogger).toOption
      .map(_.replaceAll("-([0-9]+)-g([0-9a-f]{8})", "+$1-$2"))
      .map(GitDescribeOutput.parse)
      .flatMap(output =>
        if (output.hasNoTags) getDistanceToFirstCommit().map(dist =>
          output.copy(commitSuffix = output.commitSuffix.copy(distance = dist))
        )
        else Some(output)
      )
  }

  def getGitPreviousStableTag: Option[GitDescribeOutput] = {
    for {
      // Find the parent of the current commit. The "^1" instructs it to show only the first parent,
      // as merge commits can have multiple parents
      parentHash <- execAndHandleEmptyOutput("git --no-pager log --pretty=%H -n 1 HEAD^1")
      // Find the closest tag of the parent commit
      tag <- execAndHandleEmptyOutput(s"git describe --tags --abbrev=0 --always $parentHash")
      out <- PartialFunction.condOpt(tag)(GitDescribeOutput.parse)
    } yield out
  }

  def timestamp(d: Date): String = GitDescribeOutput.timestamp(d)
  def fallback(d: Date): String = GitDescribeOutput.fallback(separator, d)

  private def execAndHandleEmptyOutput(cmd: String): Option[String] = {
    Try(Process(cmd, wd) !! impl.NoProcessLogger).toOption
      .filter(_.trim.nonEmpty)
  }
}

object DynVer extends DynVer(None, "+")
    with ((Option[File], String) => DynVer)
    with (Option[File] => DynVer)
{
  override def apply(wd: Option[File]) = apply(wd, separator)
}

object `package`

package impl {
  object NoProcessLogger extends ProcessLogger {
    def info(s: => String)  = ()
    def out(s: => String)   = ()
    def error(s: => String) = ()
    def err(s: => String)   = ()
    def buffer[T](f: => T)  = f
  }
}
