# GitHub Contributors List

Builds a contributor list based on information available on GitHub.
It reads the information of pull requests including the `Co-authored-by:` notes in commit messages.

GitHub's API [does NOT include users listed as "Co-authored-by:"](https://github.com/orgs/community/discussions/46421).
Thefore, the commit messages need to parsed "manually".

It is a rewrite of [github-contributors-list](https://github.com/mgechev/github-contributors-list) to [support `Co-authored-by:`](https://github.com/mgechev/github-contributors-list/issues/26).
`gcl` should work as drop-in replacement for `github-contributors-list`.

## How to use

1. [Install jbang](https://www.jbang.dev/documentation/guide/latest/installation.html#using-jbang).
   E.g., `curl -Ls https://sh.jbang.dev | bash -s - app setup` or (Powershell) `iex "& { $(iwr -useb https://ps.jbang.dev) } app setup"`
2. Add `oauth=...` to `~/.github` with `...` being your [GitHub personal access token](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens#creating-a-personal-access-token-classic). See [GitHub API for Java](https://github-api.kohsuke.org/) for details.
3. `./gcl.java` (or `jbang gcl.java`)

```
Usage: jbang gcl.java [-lhV] [--owner=<owner>] [--repo=<repository>] [--cols=<cols>]
           [--filter-emails=<ignoredEmails>]...
      --cols=<cols>         Number of columns
      --filter-emails=<ignoredEmails>

  -h, --help                Show this help message and exit.
  -l, --github-lookup       Should calls be made to GitHub's API for user
                              information
      --owner=<owner>       The GitHub owner of the repository
      --repo=<repository>   The GitHub repository name
  -V, --version             Print version information and exit.
```

The tool is implemeted as single pass over the commits of the repository.
It uses a cache to store the information of contributors.
Thus, repeated runs could update contributor information.
For instance, if a user first appears as "Co-authored-by:" and later as a pull request author, the username could be determined better.

In case of issues, try to delete `gcl.mv` to start with a fresh cache.

## Implementation details

- `gcl.mv` is an [MVStore](https://www.h2database.com/html/mvstore.html) caching contributor information returned by GitHub's API.
- Dependencies of `gcl.java` cannot be updated automatically. [dependabot-core#9406](https://github.com/dependabot/dependabot-core/issues/9406).
- Use `writer.level = TRACE` in tinylog.properties for debugging.
