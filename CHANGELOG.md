# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [unreleased]

### Fixed

- If email contains no `@` character, there is no exception anymore.

### Changed

- No more alignment of columns.

## [2024-07-15]

### Fixed

- Fixed a null pointer exception when running. [#22](https://github.com/koppor/github-contributors-list/issues/22)
- Fixed ignoring of users when logins are used in the filter and the commit was on the main branch.
- Fixed parsing of multiple ignored users.

## [2024-04-26]

### Added

- Improved handling of commits in mainline

### Fixed

- Fixed handling of 404 pull requests. [#11](https://github.com/koppor/github-contributors-list/issues/11)
- Fixed progressbar going backwards when using defaults.

### Changed

- Handling of logins during ignoring users.

## [2024-04-25]

### Added

- As default, [Renovate Bot](https://www.mend.io/renovate/) is ignored as author.

### Changed

- Can now run without any parameters: The current directory is used as the repository path.
- Use `--repository owner/repo` instead of `--owner` and `--repo`.
- The defaults for `--startrevision` and `--endrevision` are now the complete branch history (starting from `HEAD`). [#3](https://github.com/koppor/github-contributors-list/issues/3)

### Fixed

- Commits without any pull request number are also analyzed. [#4](https://github.com/koppor/github-contributors-list/issues/4)
- Fixed typo in parameter `--login-mapping`.
- Handling of ignored users.

### Removed

- Removed some hard-coded JabRef defaults (such as ignored users).

## [2024-04-09]

Initial release.

[unreleased]: https://github.com/koppor/github-contributors-list/compare/2024-07-15...main
[2024-07-15]: https://github.com/koppor/github-contributors-list/compare/2024-04-26...2024-07-15
[2024-04-26]: https://github.com/koppor/github-contributors-list/compare/2024-04-25...2024-04-26
[2024-04-25]: https://github.com/koppor/github-contributors-list/compare/2024-04-09...2024-04-25
[2024-04-09]: https://github.com/koppor/github-contributors-list/releases/tag/2024-04-09

<!-- markdownlint-disable-file MD024 -->
