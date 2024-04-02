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

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import me.tongfei.progressbar.ProgressBar;
import one.util.streamex.StreamEx;
import org.eclipse.collections.api.multimap.MutableMultimap;
import org.eclipse.collections.impl.factory.Multimaps;
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

@Command(name = "gcl",
        version = "gcl 0.1.0",
        mixinStandardHelpOptions = true,
        sortSynopsis = false)
public class gcl implements Callable<Integer> {

    @Option(names = "--startrevision", description = "The first revision to check (tag or commit id). Excluded.")
    private String startCommitRevStr = "v5.12";

    @Option(names = "--endrevision", description = "The last revision to check (tag or commit id). Included.")
    private String endCommitRevStr = "v5.13";

    @Option(names = "--owner", description = "The GitHub owner of the repository")
    private String owner = "JabRef";

    @Option(names = "--repo", description = "The GitHub repository name")
    private String repository = "jabref";

    @Option(names = "--cols", description = "Number of columns")
    private Integer cols  = 6;

    @Option(names = "--filter")
    private List<String> ignoredUsers = List.of("koppor", "calixtus", "Siedlerchr", "tobiasdiez", "but", "k3KAW8Pnf7mkmdSMPHz27", "HoussemNasri", "dependabot[bot]", "dependabot", "apps/dependabot", "apps/githubactions", "ThiloteE");

    @Option(names = "--filter-emails")
    private List<String> ignoredEmails = List.of(
            "118344674+github-merge-queue@users.noreply.github.com", "github-merge-queue@users.noreply.github.com", "gradle-update-robot@regolo.cc", "team@moderne.io", "49699333+dependabot[bot]@users.noreply.github.com",
            "houssemnasri2001@gmail.com", "cc.snethlage@gmail.com", "50491877+calixtus@users.noreply.github.com", "siedlerkiller@gmail.com", "Siedlerchr@users.noreply.github.com", "320228+Siedlerchr@users.noreply.github.com");

    @Option(names = { "-l", "--github-lookup" }, description = "Should calls be made to GitHub's API for user information", negatable = true)
    boolean ghLookup = true;

    @Option(names = {"-m", "--lgin-mapping"}, description = {"Mapping of GitHub logins to names. Format: name=login"})
    Map<String, String> loginMapping = Map.of("shawn.jj", "shawn-jj",
            "Harshit.Gupta7", "harsh1898",
            "Ramandeep Singh", "rdsingh13",
            "luklehnert", "lwlR",
            "Filippa Nilsson", "filippanilsson",
            "Anish.Pal", "pal-anish");

    private static final String avatarImgWidth = "117";

    private static final String githubUsersEmailSuffix = "@users.noreply.github.com";

    private record Contributor(String name, String url, String avatarUrl) implements Serializable {
    }

    private record CoAuthor(String name, String email) {
        public CoAuthor(String line) {
            this(line.substring("Co-authored-by: ".length(), line.indexOf('<')).trim(),
                    line.substring(line.indexOf('<') + 1, line.indexOf('>')).trim());
            Logger.trace("Parsed \"{}\" into {}", line, this);
        }
    }

    private SortedSet<Contributor> contributors = new TreeSet<>(Comparator.comparing(Contributor::name));

    private SortedSet<String> fallbacks = new TreeSet<>();

    private Set<CoAuthor> alreadyChecked = new HashSet<>();

    private record PRAppearance(String prNumber, String sha) {
    }

    private String currentPR = "";
    private String currentSHA = "";
    private MutableMultimap<String, PRAppearance> fallbackSources = Multimaps.mutable.sortedBag.empty(Comparator.comparing(PRAppearance::prNumber));

    public static void main(String... args) throws Exception {
        CommandLine cmd = new CommandLine(new gcl());
        int exitCode = cmd.execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        Logger.info("Connecting to {}/{}...", owner, repository);
        GitHub gitHub = GitHub.connect();
        GHRepository jabRefRepository = gitHub.getRepository(owner + "/" + repository);

        Pattern numberAtEnd = Pattern.compile(".*\\(#(\\d+)\\)$");
        Pattern mergeCommit = Pattern.compile("^Merge pull request #(\\d+) from.*");

        try (MVStore store = new MVStore.Builder().
                fileName("gcl.mv").
                open();
        Git git = Git.open(new File("C:\\git-repositories\\JabRef"));
        Repository repository = git.getRepository(); RevWalk revWalk = new RevWalk(repository)) {
            MVMap<String, Contributor> emailToContributor = store.openMap("emailToContributor");
            MVMap<String, Contributor> loginToContributor = store.openMap("loginToContributor");

            RevCommit startCommit = revWalk.parseCommit(repository.resolve(startCommitRevStr));
            RevCommit endCommit = revWalk.parseCommit(repository.resolve(endCommitRevStr));

            Instant startDate = startCommit.getAuthorIdent().getWhen().toInstant();
            Instant endDate = endCommit.getAuthorIdent().getWhen().toInstant();

            long days = Duration.between(startDate, endDate).toDays();
            Logger.info("Analyzing {} days: From {} to {}", days, DateTimeFormatter.ISO_INSTANT.format(startDate), DateTimeFormatter.ISO_INSTANT.format(endDate));

            try (ProgressBar pb = new ProgressBar("Analyzing", (int) days)) {
                pb.maxHint((int) days);

                revWalk.markStart(endCommit);
                Iterator<RevCommit> commitIterator = revWalk.iterator();

                while (commitIterator.hasNext()) {
                    RevCommit commit = commitIterator.next();
                    if (commit.equals(startCommit)) {
                        break;
                    }
                    analyzeCommit(days, startDate, commit, pb, numberAtEnd, mergeCommit, jabRefRepository, loginToContributor, emailToContributor, gitHub);
                }
            }
        }

        Logger.debug("Found contributors {}", contributors);
        Logger.debug("Used fallbacks {}", fallbacks);
        Logger.debug("Fallback source {}", fallbackSources);

        Integer maxLength = fallbacks.stream().map(String::length).max(Integer::compareTo).get();
        fallbacks.stream().forEach(fallback -> {
            String fallbackFormatted = String.format("%-" + maxLength + "s", fallback);
            fallbackSources.get(fallback).stream().forEach(pr -> {
                System.out.println(fallbackFormatted
                        + " https://github.com/%s/%s/pull/%s".formatted(owner, repository, pr.prNumber)
                        + " https://github.com/%s/%s/pull/%s/commits/%s".formatted(owner, repository, pr.prNumber, pr.sha)
                );
            });
        });

        System.out.println();

        printMarkdownSnippet();

        return 0;
    }

    private void analyzeCommit(long days, Instant startDate, RevCommit commit, ProgressBar pb, Pattern numberAtEnd, Pattern mergeCommit, GHRepository jabRefRepository, MVMap<String, Contributor> loginToContributor, MVMap<String, Contributor> emailToContributor, GitHub gitHub) throws IOException {
        long daysSinceLast = days - Duration.between(startDate, commit.getAuthorIdent().getWhen().toInstant()).toDays();
        Logger.trace("{} daysSinceLast", daysSinceLast);
        pb.stepTo((int) daysSinceLast);

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
        if (number != null) {
            analyzePullRequest(pb, jabRefRepository, loginToContributor, emailToContributor, gitHub, number);
        }
    }

    private void analyzePullRequest(ProgressBar pb, GHRepository jabRefRepository, MVMap<String, Contributor> loginToContributor, MVMap<String, Contributor> emailToContributor, GitHub gitHub, String number) throws IOException {
        Logger.trace("Investigating PR #{}", number);
        this.currentPR = number;
        pb.setExtraMessage("PR " + number);
        int prNumber = Integer.parseInt(number);
        GHPullRequest pullRequest = jabRefRepository.getPullRequest(prNumber);
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
            Optional<Contributor> contributor = lookupContributorData(emailToContributor, gitHub, authorOfCommit);
            // In case an author is ignored, the Optional is empty
            contributor.ifPresent(contributors::add);

            // Parse commit message for "Co-authored-by" hints
            theCommit.getMessage().lines()
                     .filter(line -> line.startsWith("Co-authored-by:"))
                     .map(CoAuthor::new)
                     .map(coAuthor -> lookupContributorData(emailToContributor, gitHub, coAuthor))
                     .filter(Optional::isPresent)
                     .map(Optional::get)
                     .forEach(contributors::add);
        }
    }

    private void printMarkdownSnippet() {
        StreamEx.ofSubLists(contributors.stream().toList(), cols)
                .forEach(subList -> {
                    // first line
                    List<String> elements = subList.stream()
                                                   .map(contributor -> getFormattedFirstLine(contributor))
                                                   .toList();
                    Integer maxLength = elements.stream().map(String::length).max(Integer::compareTo).get();
                    System.out.println(elements.stream()
                                               .collect(Collectors.joining(" | ", "| ", " |")));

                    // divider
                    StringJoiner divider = new StringJoiner(" | ", "| ", " |");
                    for (int i = 0; i < subList.size(); i++) {
                        divider.add( String.format("%-" + maxLength + "s", "---"));
                    }
                    System.out.println(divider);

                    // second line
                    System.out.println(subList.stream()
                                              .map(contributor -> String.format("%-" + maxLength + "s", getFormattedSecondLine(contributor)))
                                              .collect(Collectors.joining(" | ", "| ", " |")));

                    // empty line to separate
                    System.out.println();
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
            // Example: Store "LoyGhreeb" https://github.com/LoayGhreeb in userId
            String userId = contributor.url.substring(contributor.url.lastIndexOf('/') + 1);
            if (ignoredUsers.contains(userId)) {
                Logger.trace("Ignored because of userId {}: {}", userId, coAuthor);
                return Optional.empty();
            }
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
