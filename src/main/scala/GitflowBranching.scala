package config

enum GitflowBranching {
  case Feature, Develop, Release, Hotfix, Master
}

//Main Branch: This represents the production-ready state of the code.
//Develop Branch: This is the integration branch for all features and the starting point for most new work.
object GitflowBranching {
  def branchInfo(branch: GitflowBranching): String = {
    branch match {
      case GitflowBranching.Feature =>
        "Feature branches are used to develop new features."
      case GitflowBranching.Develop =>
        "Develop branch is the integration branch for features."
      case GitflowBranching.Release =>
        "Release branches support preparation of a new production release."
      case GitflowBranching.Hotfix =>
        "Hotfix branches are for quick patches to production releases."
      case GitflowBranching.Master =>
        "Master branch contains the production-ready code."
    }
  }

  /** Lists all branch types with their descriptions.
    */
  def allBranchesInfo(): Map[String, String] =
    GitflowBranching.values.map(b => b.toString -> branchInfo(b)).toMap

  /** Pretty-prints all branches and their roles.
    */
  def printBranchSummary(): Unit =
    allBranchesInfo().foreach { case (name, info) =>
      println(s"[$name] -> $info")
    }

// -- Allowed base branch types when creating a branch of a given kind
// e.g. a Feature branch should be created from Develop
  private val allowedCreationBase: Map[GitflowBranching, Set[
    (GitflowBranching, GitflowBranching | (GitflowBranching, GitflowBranching))
  ]] = Map(
    // Feature branches are created from Develop and merged back into Develop
    GitflowBranching.Feature -> Set(
      GitflowBranching.Develop -> GitflowBranching.Develop
    ),
    // Release branches are created from Develop and merged into both Master and Develop
    // merging back into Develop ensures any changes made during release preparation are not lost
    // merge back into Develop ensures any bug fixes made during release prep are retained
    GitflowBranching.Release -> Set(
      GitflowBranching.Develop -> (
        GitflowBranching.Develop,
        GitflowBranching.Master
      )
    ),
    // Used to quickly fix critical bugs found in the production environment.
    // Hotfix branches are created from Master and merged back into both Master and Develop
    GitflowBranching.Hotfix -> Set(
      GitflowBranching.Master -> (
        GitflowBranching.Master,
        GitflowBranching.Develop
      )
    ),
    GitflowBranching.Develop -> Set(
      GitflowBranching.Master -> GitflowBranching.Release
    ), // initial develop branch created from master and develop merges back into master via release branch
    GitflowBranching.Master -> Set() // don't create master from other branches in normal flow
  )

// -- Allowed merge targets for each source type
  // e.g. Feature branches may only be merged into Develop
  private val allowedMergeTargets
      : Map[GitflowBranching, Set[GitflowBranching]] = Map(
    GitflowBranching.Feature -> Set(GitflowBranching.Develop),
    GitflowBranching.Develop -> Set(
      GitflowBranching.Release
    ), // develop -> release (prepare release)
    GitflowBranching.Release -> Set(
      GitflowBranching.Master,
      GitflowBranching.Develop
    ),
    GitflowBranching.Hotfix -> Set(
      GitflowBranching.Master,
      GitflowBranching.Develop
    ),
    GitflowBranching.Master -> Set() // master is terminal for merges (hotfixes branch from it)
  )

}
