///usr/bin/env jbang "$0" "$@" ; exit $?

//DEPS com.h2database:h2-mvstore:2.2.224
//DEPS org.eclipse.jgit:org.eclipse.jgit:6.9.0.202403050737-r
//DEPS org.kohsuke:github-api:1.321

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterator;
import org.kohsuke.github.PagedSearchIterable;

import static java.lang.System.*;

public class gcl {

    private static final boolean GH_LOOKUP = false;

    private record Contributor(String name, String url, String avatarUrl) implements Serializable {
    }

    private record CoAuthor(String name, String email) {
        public CoAuthor(String line) {
            this(line.substring("Co-authored-by: ".length(), line.indexOf('<')).trim(),
                    line.substring(line.indexOf('<') + 1, line.indexOf('>')).trim());
        }
    }

    private static Set ignoredEmails = Set.of(
            "118344674+github-merge-queue@users.noreply.github.com", "github-merge-queue@users.noreply.github.com", "gradle-update-robot@regolo.cc", "team@moderne.io", "49699333+dependabot[bot]@users.noreply.github.com",
            "houssemnasri2001@gmail.com", "cc.snethlage@gmail.com", "50491877+calixtus@users.noreply.github.com", "siedlerkiller@gmail.com", "Siedlerchr@users.noreply.github.com", "320228+Siedlerchr@users.noreply.github.com");

    public static void main(String... args) throws Exception {
        SortedSet<Contributor> contributors = new TreeSet<>(Comparator.comparing(Contributor::name));

        GitHub gitHub = GitHub.connect();

        try (MVStore store = new MVStore.Builder().
                fileName("gcl.mv").
                open();
        Git git = Git.open(new File("C:\\git-repositories\\JabRef"));
        Repository repository = git.getRepository(); RevWalk revWalk = new RevWalk(repository)) {
            MVMap<String, Contributor> emailToContributor = store.openMap("emailToContributor");
            RevCommit v512 = revWalk.parseCommit(repository.resolve("v5.12"));
            RevCommit v513 = revWalk.parseCommit(repository.resolve("v5.13"));
            revWalk.markStart(v513);
            Iterator<RevCommit> commitIterator = revWalk.iterator();

            while (commitIterator.hasNext()) {
                RevCommit commit = commitIterator.next();
                if (commit.equals(v512)) {
                    break;
                }

                // Parse commit meta data
                String email = commit.getAuthorIdent().getEmailAddress();
                if ((emailToContributor.get(email) == null) && (!ignoredEmails.contains(email))) {
                    Contributor contributor = lookupContributorData(emailToContributor, gitHub, new CoAuthor(commit.getAuthorIdent().getName(), email));
                    contributors.add(contributor);
                }

                // Parse commit message for "Co-authored-by" hints
                commit.getFullMessage().lines()
                        .filter(line -> line.startsWith("Co-authored-by:"))
                        .map(CoAuthor::new)
                        .filter(coAuthor -> !ignoredEmails.contains(coAuthor.email))
                        .filter(coAuthor -> !emailToContributor.containsKey(coAuthor.email))
                        .map(coAuthor -> lookupContributorData(emailToContributor, gitHub, coAuthor))
                        .forEach(contributors::add);
            }
        }

        out.println("Done");

        contributors.forEach(c -> out.println(c));
    }

    private static Contributor lookupContributorData(MVMap<String, Contributor> emailToContributor, GitHub gitHub, CoAuthor coAuthor) {
        out.println("Looking up " + coAuthor);
        final String email = coAuthor.email;
        Contributor contributor = emailToContributor.get(email);
        if (contributor == null) {
            if (!GH_LOOKUP) {
                out.println("Online lookup disabled. Using " + coAuthor.name + " as fallback.");
                return new Contributor(coAuthor.name, "", "");
            }
            PagedSearchIterable<GHUser> list = gitHub.searchUsers().q(email).list();
            String lookup = email;
            if ((list.getTotalCount() == 0) && (email.contains("+"))) {
                lookup = email.substring(email.indexOf('+') + 1);
                out.println("Looking up " + lookup);
                list = gitHub.searchUsers().q(lookup).list();
            }
            if (list.getTotalCount() == 0) {
                lookup = lookup.substring(0, lookup.indexOf('@'));
                out.println("Looking up " + lookup);
                list = gitHub.searchUsers().q(lookup).list();
            }
            if (list.getTotalCount() == 0) {
                lookup = coAuthor.name;
                out.println("Looking up " + lookup);
                list = gitHub.searchUsers().q(lookup).list();
            }
            if (list.getTotalCount() == 0) {
                out.println("No user found for " + coAuthor + ". Using " + coAuthor.name + " as fallback.");
                return new Contributor(coAuthor.name, "", "");
            }
            if (list.getTotalCount() == 1) {
                GHUser user = list.iterator().next();
                Contributor newContributor = new Contributor(user.getLogin(), user.getHtmlUrl().toString(), user.getAvatarUrl());
                emailToContributor.put(email, newContributor);
                return newContributor;
            }
            out.println("Multiple users found for " + coAuthor + ". Checking " + lookup + "...");
            PagedIterator<GHUser> iterator = list.iterator();
            while (iterator.hasNext()) {
                GHUser user = iterator.next();
                if (user.getLogin().equalsIgnoreCase(lookup)) {
                    Contributor newContributor = new Contributor(user.getLogin(), user.getHtmlUrl().toString(), user.getAvatarUrl());
                    emailToContributor.put(email, newContributor);
                    out.println("Found " + newContributor + " for " + coAuthor);
                    return newContributor;
                }
            }
            out.println("No user found for " + coAuthor + ". Using " + coAuthor.name + " as fallback.");
            return new Contributor(coAuthor.name, "", "");
        } else {
            return contributor;
        }
    }
}
