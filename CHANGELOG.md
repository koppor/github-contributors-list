# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## 2024-04-25

### Added

### Changed

- Can now run without any parameters: The current directory is used as the repository path.
- Use `--repository owner/repo` instead of `--owner` and `--repo`.
- The defaults for `--startrevision` and `--endrevision` are now the complete branch history (starting from `HEAD`).

### Fixed

- Commits without any pull request number are also analyzed.
- Fixed typo in parameter `--login-mapping`.

### Removed

- Removed some hard-coded JabRef defaults (such as ignored users).

## 2024-04-09

Initial release.
