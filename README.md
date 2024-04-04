# GitHub Contributors List

Builds a contributor list based on information available on GitHub.
It reads the information of pull requests including the `Co-authored-by:` notes in commit messages.

GitHub's API [does NOT include users listed as "Co-authored-by:"](https://github.com/orgs/community/discussions/46421).
Thefore, the commit messages need to parsed "manually".

It is a rewrite of [github-contributors-list](https://github.com/mgechev/github-contributors-list) to [support `Co-authored-by:`](https://github.com/mgechev/github-contributors-list/issues/26).

## Example

See <https://blog.jabref.org/2024/04/03/JabRef5-13/#special-thanks> for real-world usage.

## How to use

1. [Install jbang](https://www.jbang.dev/documentation/guide/latest/installation.html#using-jbang).
   E.g., `curl -Ls https://sh.jbang.dev | bash -s - app setup` or (Powershell) `iex "& { $(iwr -useb https://ps.jbang.dev) } app setup"`
2. Add `oauth=...` to `~/.github` with `...` being your [GitHub personal access token](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens#creating-a-personal-access-token-classic). See [GitHub API for Java](https://github-api.kohsuke.org/) for details.
3. `jbang https://github.com/koppor/github-contributors-list/blob/HEAD/gcl.java --owner=<owner> --repo=<repository> --startrevision=<startCommitRevStr> --endrevision=<endCommitRevStr> <repositoryPath>`

```
Usage: jbang gcl.java [-lhV] [--startrevision=<startCommitRevStr>]
           [--endrevision=<endCommitRevStr>] [--owner=<owner>]
           [--repo=<repository>] [--cols=<cols>] [--filter=<ignoredUsers>]...
           [--filter-emails=<ignoredEmails>]... [-m=<String=String>]...
           <repositoryPath>
      <repositoryPath>       The path to the git repository to analyse.
      --cols=<cols>          Number of columns
      --endrevision=<endCommitRevStr>
                             The last revision to check (tag or commit id).
                               Included.
      --filter=<ignoredUsers>

      --filter-emails=<ignoredEmails>

  -h, --help                 Show this help message and exit.
  -l, --[no-]github-lookup   Should calls be made to GitHub's API for user
                               information
  -m, --lgin-mapping=<String=String>
                             Mapping of GitHub logins to names. Format:
                               name=login
      --owner=<owner>        The GitHub owner of the repository
      --repo=<repository>    The GitHub repository name
      --startrevision=<startCommitRevStr>
                             The first revision to check (tag or commit id).
                               Excluded.
  -V, --version              Print version information and exit.
```

At the end, non-found committers are listed.
The format is `<used name> <PR link> <commit link>`.
Example:

    Anish.Pal https://github.com/JabRef/jabref/pull/10829 https://github.com/JabRef/jabref/pull/10829/commits/d2d84923df2c6c7d59559da8d583ae17dc803c3d

With that information, one can create a mapping from the committer name to the GitHub username.
In this case: `Anish.Pal=pal-anish`

The tool is implemented as single pass over the commits of the repository.
It uses a cache to store the information of contributors.
Thus, repeated runs could update contributor information.
For instance, if a user first appears as "Co-authored-by:" and later as a pull request author, the username could be determined better.

In case of issues, try to delete `gcl.mv` to start with a fresh cache.

## Implementation details

- `gcl.mv` is an [MVStore](https://www.h2database.com/html/mvstore.html) caching contributor information returned by GitHub's API.
- Dependencies of `gcl.java` cannot be updated automatically. [dependabot-core#9406](https://github.com/dependabot/dependabot-core/issues/9406).
- Use `writer.level = TRACE` in tinylog.properties for debugging.

## Alternatives

- Manually curate all contributors using [All Contributors](https://allcontributors.org/)