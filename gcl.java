///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 21+

//DEPS com.h2database:h2-mvstore:2.2.224
//DEPS org.eclipse.jgit:org.eclipse.jgit:6.9.0.202403050737-r
//DEPS org.kohsuke:github-api:1.321
//DEPS info.picocli:picocli:4.7.5
//DEPS one.util:streamex:0.8.2
//DEPS me.tongfei:progressbar:0.10.1
//DEPS org.eclipse.collections:eclipse-collections:11.1.0

//DEPS org.tinylog:tinylog-api:2.7.0
//DEPS org.tinylog:tinylog-impl:2.7.0
//DEPS org.tinylog:slf4j-tinylog:2.7.0 // because of jgit
//FILES tinylog.properties

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import me.tongfei.progressbar.ProgressBar;
import one.util.streamex.StreamEx;
import org.eclipse.collections.api.multimap.MutableMultimap;
import org.eclipse.collections.impl.factory.Multimaps;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.revwalk.RevSort;
import org.kohsuke.github.GHFileNotFoundException;
import org.tinylog.Logger;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestCommitDetail;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterator;
import org.kohsuke.github.PagedSearchIterable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "gcl",
        version = "gcl 0.1.0",
        mixinStandardHelpOptions = true,
        sortSynopsis = false)
public class gcl implements Callable<Integer> {

    private static boolean hasStartRevision;
    private static boolean hasEndRevision;
    private static boolean hasRepository;

    private static final Pattern numberAtEnd = Pattern.compile(".*\\(#(\\d+)\\)$");
    private static final Pattern mergeCommit = Pattern.compile("^Merge pull request #(\\d+) from.*");

    @Parameters(index = "0", arity = "0..1", description = "The path to the git repository to analyse.")
    private Path repositoryPath = Path.of(".");

    @Option(names = "--startrevision", description = "The first revision to check (tag or commit id). Excluded.")
    private String startCommitRevStr;

    @Option(names = "--endrevision", description = "The last revision to check (tag or commit id). Included.")
    private String endCommitRevStr;

    @Option(names = "--repository", description = "The GitHub repository in the form owner/repostiory. E.g., JabRef/jabref")
    private String ownerRepository;

    @Option(names = "--cols", description = "Number of columns")
    private Integer cols  = 6;

    @Option(names = "--filter")
    private List<String> ignoredUsers = List.of(
            "dependabot[bot]",
            "dependabot",
            "apps/dependabot",
            "apps/githubactions",
            "renovate[bot]",
            "renovate-bot");

    @Option(names = "--filter-emails")
    private List<String> ignoredEmails = List.of(
            "49699333+dependabot[bot]@users.noreply.github.com",
            "bot@renovateapp.com",
            "118344674+github-merge-queue@users.noreply.github.com",
            "github-merge-queue@users.noreply.github.com",
            "gradle-update-robot@regolo.cc",
            "team@moderne.io");

    @Option(names = { "-l", "--github-lookup" }, description = "Should calls be made to GitHub's API for user information", negatable = true)
    boolean ghLookup = true;

    @Option(names = {"-m", "--login-mapping"}, description = {"Mapping of GitHub logins to names. Format: name=login"})
    Map<String, String> loginMapping = Map.of("shawn.jj", "shawn-jj",
            "Harshit.Gupta7", "harsh1898",
            "Ramandeep Singh", "rdsingh13",
            "luklehnert", "lwlR",
            "Filippa Nilsson", "filippanilsson",
            "Houssem Nasri", "HoussemNasri",
            "Anish.Pal", "pal-anish");

    private static final String avatarImgWidth = "117";

    private static final String githubUsersEmailSuffix = "@users.noreply.github.com";

    private record Contributor(String name, String url, String avatarUrl) implements Serializable {
        public String getUserId() {
            // Example: https://github.com/LoayGhreeb, then userId is LoeyGhreeb
            return url.substring(url.lastIndexOf('/') + 1);
        }
    }

    private record CoAuthor(String name, String email) {
        public CoAuthor(String line) {
            this(line.substring("Co-authored-by: ".length(), line.indexOf('<')).trim(),
                    line.substring(line.indexOf('<') + 1, line.indexOf('>')).trim());
            Logger.trace("Parsed \"{}\" into {}", line, this);
        }
    }

    private SortedSet<Contributor> contributors = new TreeSet<>((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name));

    private SortedSet<String> fallbacks = new TreeSet<>();

    private Set<CoAuthor> alreadyChecked = new HashSet<>();

    private record PRAppearance(String prNumber, String sha) {
    }

    private String currentPR = "";
    private String currentSHA = "";
    private MutableMultimap<String, PRAppearance> fallbackSources = Multimaps.mutable.sortedBag.empty(Comparator.comparing(PRAppearance::prNumber));

    public static void main(String... args) throws Exception {
        CommandLine commandLine = new CommandLine(new gcl());

        CommandLine.ParseResult parseResult = commandLine.parseArgs(args);
        hasStartRevision = parseResult.hasMatchedOption("--startrevision");
        hasEndRevision = parseResult.hasMatchedOption("--endrevision");
        hasRepository = parseResult.hasMatchedOption("--repository");

        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }

    private static void setDefaultValues(String[] args, CommandLine commandLine) {
    }

    @Override
    public Integer call() throws Exception {
        Logger.info("Opening local git repository {}...", repositoryPath);

        if (!Files.exists(repositoryPath)) {
            Logger.error("Path {} not found", repositoryPath);
            return 1;
        }

        try (MVStore store = new MVStore.Builder().
                fileName("gcl.mv").
                open();
            Git git = Git.open(repositoryPath.toFile());
            Repository repository = git.getRepository()) {

            RepositoryData repositoryData = getRepositoryData(git, store, repository);
            if (repositoryData == null) {
                return 1;
            }

            DateTimeFormatter isoDateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault());
            Logger.info("Analyzing {} days backwards: From {} to {}",
                    repositoryData.days(),
                    isoDateTimeFormatter.format(repositoryData.endDate()),
                    isoDateTimeFormatter.format(repositoryData.startDate()));

            MVMap<String, Contributor> loginToContributor = store.openMap("loginToContributor");
            MVMap<String, Contributor> emailToContributor = store.openMap("emailToContributor");

            try (ProgressBar pb = new ProgressBar("Analyzing", (int) repositoryData.days());
                 RevWalk revWalk = new RevWalk(repository)) {
                revWalk.markStart(repositoryData.endCommit());
                Iterator<RevCommit> commitIterator = revWalk.iterator();

                while (commitIterator.hasNext()) {
                    RevCommit commit = commitIterator.next();
                    if (commit.equals(repositoryData.startCommit())) {
                        break;
                    }
                    analyzeCommit(pb, repositoryData.gitHub(), repositoryData.gitHubRepository(), loginToContributor, emailToContributor, repositoryData.startDate(), commit);
                }
            }
        }

        Logger.debug("Found contributors {}", contributors);
        Logger.debug("Used fallbacks {}", fallbacks);
        Logger.debug("Fallback source {}", fallbackSources);

        if (!fallbacks.isEmpty()) {
            outputFallbacks();
        }

        System.out.println();

        printMarkdownSnippet();

        return 0;
    }

    private RepositoryData getRepositoryData(Git git, MVStore store, Repository repository) throws IOException, GitAPIException {
        String remoteOriginUrl = "n/a";
        if (!hasRepository) {
            // Source: https://stackoverflow.com/a/38062680/873282
            remoteOriginUrl = git.getRepository().getConfig().getString("remote", "origin", "url");
            if (remoteOriginUrl.startsWith("git@")) {
                ownerRepository = remoteOriginUrl.substring(remoteOriginUrl.indexOf(':') + 1, remoteOriginUrl.lastIndexOf('.'));
            } else {
                ownerRepository = remoteOriginUrl.substring(remoteOriginUrl.indexOf("github.com/") + "github.com/".length());
                if (ownerRepository.endsWith(".git")) {
                    ownerRepository = ownerRepository.substring(0, ownerRepository.length() - ".git".length());
                }
            }
        }

        Logger.info("Connecting to {}...", ownerRepository);
        GitHub gitHub = GitHub.connect();
        GHRepository gitHubRepository;
        try {
            gitHubRepository = gitHub.getRepository(ownerRepository);
        } catch (IllegalArgumentException e) {
            Logger.error("Error in repository reference {}", ownerRepository);
            if (!hasRepository) {
                Logger.error("It was automatically derived from {}", remoteOriginUrl);
            }
            return null;
        }

        RevCommit startCommit;
        RevCommit endCommit;
        try (RevWalk revWalk = new RevWalk(repository)) {
            if (hasStartRevision) {
                startCommit = revWalk.parseCommit(repository.resolve(startCommitRevStr));
            } else {
                RevCommit root = revWalk.parseCommit(repository.resolve(Constants.HEAD));
                revWalk.sort(RevSort.REVERSE);
                revWalk.markStart(root);
                startCommit = revWalk.next();
                Logger.trace("No explicit start commit, using {}", startCommit.getId().name());
            }

            if (hasEndRevision) {
                endCommit = revWalk.parseCommit(repository.resolve(endCommitRevStr));
            } else {
                // Hint by https://stackoverflow.com/a/59274329/873282
                endCommit = git.log().setMaxCount(1).call().iterator().next();
                Logger.trace("No explicit end commit, using {}", endCommit.getId().name());
            }

        }

        Instant startDate = startCommit.getAuthorIdent().getWhen().toInstant();
        Instant endDate = endCommit.getAuthorIdent().getWhen().toInstant();

        if (startDate.isAfter(endDate)) {
            Logger.warn("Start date is after end date. Swapping.");
            // Swap start and end date
            Instant tmp = endDate;
            endDate = startDate;
            startDate = tmp;
        }

        long days = Duration.between(startDate, endDate).toDays();

        return new RepositoryData(gitHub, gitHubRepository, startCommit, startDate, endCommit, endDate, days);
    }

    private record RepositoryData(GitHub gitHub, GHRepository gitHubRepository, RevCommit startCommit, Instant startDate, RevCommit endCommit, Instant endDate, long days) {
    }

    private void outputFallbacks() {
        System.out.println();
        System.out.println("Fallbacks:");
        System.out.println();

        Integer maxLength = fallbacks.stream().map(String::length).max(Integer::compareTo).get();
        fallbacks.stream().forEach(fallback -> {
            String fallbackFormatted = String.format("%-" + maxLength + "s", fallback);
            fallbackSources.get(fallback).stream().forEach(pr -> {
                System.out.println(fallbackFormatted
                        + " https://github.com/%s/pull/%s".formatted(ownerRepository, pr.prNumber)
                        + " https://github.com/%s/pull/%s/commits/%s".formatted(ownerRepository, pr.prNumber, pr.sha)
                );
            });
        });
    }

    /**
     * Analyzes a commit found in the repository. Will then diverge to the pull request analysis if a PR number is found in the commit message. Otherwise, the commit is analyzed as a regular commit.
     */
    private void analyzeCommit(ProgressBar progressBar, GitHub gitHub, GHRepository ghRepository, MVMap<String, Contributor> loginToContributor, MVMap<String, Contributor> emailToContributor, Instant startDate, RevCommit commit) throws IOException {
        long daysSinceFirstCommit = Duration.between(startDate, commit.getAuthorIdent().getWhen().toInstant()).toDays();
        Logger.trace("{} daysSinceFirstCommit", daysSinceFirstCommit);

        progressBar.stepTo(Math.max((int) daysSinceFirstCommit, progressBar.getCurrent()));

        String shortMessage = commit.getShortMessage();
        Logger.trace("Checking commit \"{}\" ({})", shortMessage, commit.getId().name());

        Matcher matcher = numberAtEnd.matcher(shortMessage);
        String number = null;
        if (matcher.matches()) {
            number = matcher.group(1);
        }
        if (number == null) {
            matcher = mergeCommit.matcher(shortMessage);
            if (matcher.matches()) {
                number = matcher.group(1);
            }
        }
        if (number == null) {
            Logger.trace("No PR number found in commit message");
            addContributorFromRevCommit(commit, emailToContributor, gitHub);
        } else {
            if (!analyzePullRequest(progressBar, ghRepository, loginToContributor, emailToContributor, gitHub, number)) {
                Logger.trace("PR was 404. Interpreting commit itself");
                addContributorFromRevCommit(commit, emailToContributor, gitHub);
            }
        }
    }

    private void addContributorFromRevCommit(RevCommit commit, MVMap<String, Contributor> emailToContributor, GitHub gitHub) {
        CoAuthor authorOfCommit = new CoAuthor(commit.getAuthorIdent().getName(), commit.getAuthorIdent().getEmailAddress());
        analyzeRegularCommit(authorOfCommit, emailToContributor, gitHub, commit.getFullMessage());
    }

    /**
     * @return false if the PR did not exist
     */
    private boolean analyzePullRequest(ProgressBar progressBar, GHRepository ghRepository, MVMap<String, Contributor> loginToContributor, MVMap<String, Contributor> emailToContributor, GitHub gitHub, String number) throws IOException {
        Logger.trace("Investigating PR #{}", number);
        this.currentPR = number;
        progressBar.setExtraMessage("PR " + number);
        int prNumber = Integer.parseInt(number);
        GHPullRequest pullRequest;
        try {
            pullRequest = ghRepository.getPullRequest(prNumber);
        } catch (GHFileNotFoundException e) {
            Logger.warn("Pull request {} not found", prNumber);
            return false;
        }
        GHUser user = pullRequest.getUser();
        storeContributorData(loginToContributor, emailToContributor, user);

        PagedIterator<GHPullRequestCommitDetail> ghCommitIterator = pullRequest.listCommits().iterator();
        while (ghCommitIterator.hasNext()) {
            GHPullRequestCommitDetail ghCommit = ghCommitIterator.next();
            GHPullRequestCommitDetail.Commit theCommit = ghCommit.getCommit();

            this.currentSHA = ghCommit.getSha();

            // GitHub's API does not set the real GitHub username, so following does not work
            // This is very different from the information, which is available in GitHub's web interface
            // storeContributorData(loginToContributor, gitHub, theCommit.getAuthor().getUsername());
            // storeContributorData(loginToContributor, gitHub, theCommit.getCommitter().getUsername());

            CoAuthor authorOfCommit = new CoAuthor(theCommit.getAuthor().getName(), theCommit.getAuthor().getEmail());
            analyzeRegularCommit(authorOfCommit, emailToContributor, gitHub, theCommit.getMessage());
        }

        return true;
    }

    private void analyzeRegularCommit(CoAuthor authorOfCommit, MVMap<String, Contributor> emailToContributor, GitHub gitHub, String commitMessage) {
        Optional<Contributor> contributor = lookupContributorData(emailToContributor, gitHub, authorOfCommit);
        // In case an author is ignored, the Optional is empty
        contributor.ifPresent(contributors::add);

        // Parse commit message for "Co-authored-by" hints
        commitMessage.lines()
                 .filter(line -> line.startsWith("Co-authored-by:"))
                 .map(CoAuthor::new)
                 .map(coAuthor -> lookupContributorData(emailToContributor, gitHub, coAuthor))
                 .filter(Optional::isPresent)
                 .map(Optional::get)
                 .forEach(contributors::add);
    }

    private void printMarkdownSnippet() {
        String heading = "|" + "  |".repeat(cols);
        System.out.println(heading);

        String headingSeparator = "|" + " --  |".repeat(cols);
        System.out.println(headingSeparator);

        StreamEx.ofSubLists(contributors.stream().toList(), cols)
                .forEach(subList -> {
                    boolean isShorterList = subList.size() < cols;

                    // first line
                    List<String> elements = subList.stream()
                                                   .map(contributor -> getFormattedFirstLine(contributor))
                                                   .toList();
                    Integer maxLength = elements.stream().map(String::length).max(Integer::compareTo).get();

                    String suffix = "";
                    if (isShorterList) {
                        suffix = "  |".repeat(cols - subList.size());
                    }

                    System.out.println(elements.stream()
                                               .collect(Collectors.joining(" | ", "| ", " |")) + suffix);

                    // second line
                    System.out.println(subList.stream()
                                              .map(contributor -> String.format("%-" + maxLength + "s", getFormattedSecondLine(contributor)))
                                              .collect(Collectors.joining(" | ", "| ", " |")) + suffix);
                });
    }

    private static String getFormattedFirstLine(Contributor contributor) {
        if (contributor.url().isEmpty()) {
            return contributor.name();
        }
        return """
            [<img alt="%s" src="%s&w=%4$s" width="%4$s">](%3$s)""".formatted(contributor.name(), contributor.avatarUrl(), contributor.url(), avatarImgWidth);
    }

    private static String getFormattedSecondLine(Contributor contributor) {
        if (contributor.url().isEmpty()) {
            return "";
        }
        return """
                [%s](%s)""".formatted(contributor.name(), contributor.url());
    }

    /**
     * Derives the contributor based on given ghUser and adds it to the loginToContributor and emailToContributor maps as well as to the contributors set.
     */
    private void storeContributorData(MVMap<String, Contributor> loginToContributor, MVMap<String, Contributor> emailToContributor, GHUser ghUser) {
        Logger.trace("Handling {}", ghUser);
        String login = ghUser.getLogin();

        Contributor contributor = loginToContributor.get(login);
        Logger.trace("Found contributor {}", contributor);
        if (contributor != null) {
            if (ignoredUsers.contains(login)) {
                Logger.trace("Ignored because of login {}", login);
                return;
            }
            contributors.add(contributor);
            putIntoEmailToContributorMap(emailToContributor, ghUser, contributor);
            return;
        }

        String name;
        try {
            name = ghUser.getName();
        } catch (IOException e) {
            name = login;
        }
        if (name == null) {
            Logger.debug("Could not get name for {}. Falling back to login.", ghUser, login);
            name = login;
        }

        if (ignoredUsers.contains(name)) {
            Logger.trace("Ignored because of name {}", name);
            return;
        }

        Contributor newContributor = new Contributor(name, ghUser.getHtmlUrl().toString(), ghUser.getAvatarUrl());
        Logger.trace("Created new contributor {} based on PR data", newContributor);
        loginToContributor.put(login, newContributor);
        contributors.add(newContributor);

        putIntoEmailToContributorMap(emailToContributor, ghUser, newContributor);
    }

    private static void putIntoEmailToContributorMap(MVMap<String, Contributor> emailToContributor, GHUser ghUser, Contributor contributor) {
        // Store in emailToContributor map to enable lookup at Co-authored-by handling
        String email = null;
        try {
            email = ghUser.getEmail();
        } catch (IOException e) {
            // Ignored
        }
        if (email == null) {
            email = ghUser.getLogin() + githubUsersEmailSuffix;
            Logger.trace("No email found for {}. Setting fallback email {}", ghUser, email);
        }
        Logger.trace("Added map entry {}", email);
        emailToContributor.put(email, contributor);
    }

    private Optional<Contributor> lookupContributorData(MVMap<String, Contributor> emailToContributor, GitHub gitHub, CoAuthor coAuthor) {
        Logger.trace("Looking up {}", coAuthor);
        if (alreadyChecked.contains(coAuthor)) {
            Logger.trace("Already checked {}", coAuthor);
            return Optional.empty();
        }
        alreadyChecked.add(coAuthor);

        if (contributors.stream().anyMatch(contributor -> contributor.name.equalsIgnoreCase(coAuthor.name))) {
            Logger.trace("Already found {}", coAuthor);
            return Optional.empty();
        }

        final String email = coAuthor.email;

        if (ignoredEmails.contains(email)) {
            Logger.trace("Ignored because of email: {}", coAuthor);
            return Optional.empty();
        }

        if (ignoredUsers.contains(coAuthor.name)) {
            Logger.trace("Ignored because of name: {}", coAuthor);
            return Optional.empty();
        }

        Contributor contributor = emailToContributor.get(email);
        if (contributor != null) {
            // We already know this email, just check whether we ignore this one
            String userId = contributor.getUserId();
            if (ignoredUsers.contains(userId)) {
                Logger.trace("Ignored because of userId {}: {}", userId, coAuthor);
                return Optional.empty();
            }
            return Optional.of(contributor);
        }

        if (!ghLookup) {
            Logger.trace("Online lookup disabled. Using {} as fallback.", coAuthor.name);
            fallbacks.add(coAuthor.name);
            fallbackSources.put(coAuthor.name, new PRAppearance(currentPR, currentSHA));
            return Optional.of(new Contributor(coAuthor.name, "", ""));
        }
        PagedSearchIterable<GHUser> list = gitHub.searchUsers().q(email).list();
        if (list.getTotalCount() == 1) {
            GHUser user = list.iterator().next();
            String login = user.getLogin();
            if (ignoredUsers.contains(login)) {
                Logger.trace("Ignored because of login {}: {}", login, coAuthor);
                return Optional.empty();
            }
            Contributor newContributor = new Contributor(login, user.getHtmlUrl().toString(), user.getAvatarUrl());
            emailToContributor.put(email, newContributor);
            return Optional.of(newContributor);
        }
        if (list.getTotalCount() > 1) {
            Logger.error("Multiple users found for the email of {}. Ignoring", coAuthor);
            return Optional.empty();
        }

        String lookup = email;

        GHUser user = null;

        if (email.contains("+")) {
            Logger.trace("Found + in email. Removing the part before it.");
            lookup = email.substring(email.indexOf('+') + 1);
        }

        if (user == null) {
            Logger.trace("Trying to find username derived from email.");
            lookup = lookup.substring(0, lookup.indexOf('@'));
            Logger.trace("Looking up {}", lookup);
            try {
                user = gitHub.getUser(lookup);
            } catch (IOException e) {
                Logger.trace("User not found for {}", lookup);
            }
        }

        if (user == null) {
            Logger.trace("No results. Trying to find username equal to name.");
            lookup = coAuthor.name;
            Logger.trace("Looking up {}", lookup);
            try {
                user = gitHub.getUser(lookup);
            } catch (IOException e) {
                Logger.trace("User not found for {}", lookup);
            }
        }

        if (user == null) {
            String mappedLogin = loginMapping.get(lookup);
            if (mappedLogin != null) {
                Logger.trace("No results. Trying to find mapped login {}", mappedLogin);
                try {
                    user = gitHub.getUser(mappedLogin);
                } catch (IOException e) {
                    Logger.trace("User not found for {}", mappedLogin);
                }
            }
        }

        if (user == null) {
            Logger.trace("No user found for {}. Using {} as fallback.", coAuthor, coAuthor.name);
            fallbacks.add(coAuthor.name);
            fallbackSources.put(coAuthor.name, new PRAppearance(currentPR, currentSHA));
            return Optional.of(new Contributor(coAuthor.name, "", ""));
        }

        String login = user.getLogin();
        if (ignoredUsers.contains(login)) {
            Logger.trace("Ignored because of login {}: {}", login, coAuthor);
            return Optional.empty();
        }

        String name = null;
        try {
            name = user.getName();
        } catch (IOException e) {
            // handled later in the == null case
        }
        if (name == null) {
            String usersLogin = user.getLogin();
            Logger.debug("Could not get name for {}. Falling back to login {}", user, usersLogin);
            name = usersLogin;
        }

        Contributor newContributor = new Contributor(name, user.getHtmlUrl().toString(), user.getAvatarUrl());

        Logger.trace("Found user {} for {}", newContributor, coAuthor);

        emailToContributor.put(email, newContributor);
        return Optional.of(newContributor);
    }
}
