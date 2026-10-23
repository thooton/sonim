package com.hbsa.xp3;

import com.xp3.common.Check;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

/**
 * Everything in :core, checked on a desktop JVM. The pages here are written
 * for the tests in Instagram's shape, not copied from it.
 */
public final class SelfTest {

    static final TimeZone PHOENIX = TimeZone.getTimeZone("America/Phoenix");

    static long at(String time) {
        return OffsetDateTime.parse(time).toInstant().toEpochMilli();
    }

    /** A media id made at `time`, as Instagram's snowflakes are. */
    static String pk(long time) {
        return java.math.BigInteger.valueOf(time - Instagram.EPOCH_MS).shiftLeft(23).add(java.math.BigInteger.valueOf(12345)).toString();
    }

    static String node(String code, long time, String caption, String extra) {
        return "{\"node\":{\"code\":\"" + code + "\",\"pk\":\"" + pk(time) + "\",\"media_type\":1,"
            + "\"caption\":{\"text\":\"" + caption + "\"},\"display_uri\":\"https://cdn.example/" + code + ".jpg\""
            + extra + ",\"like_count\":12}}";
    }

    public static void main(String[] args) {
        final Check check = new Check();
        final long posted1 = at("2026-10-21T18:00:00-07:00");
        final long posted2 = at("2026-10-14T09:30:00-07:00");
        final long pinned = at("2026-08-20T12:00:00-07:00");

        check.run("profile page", () -> {
            String page = "<html><head><script type=\"application/json\" data-sjs>{\"other\":1}</script>"
                + "<script type=\"application/json\" data-content-len=\"9\" data-sjs>{\"require\":[[\"x\",{\"__bbox\":{\"result\":{\"data\":"
                + "{\"xdt_api__v1__feed__user_timeline_graphql_connection\":null,\"polaris_ordered_timeline_connection\":{\"edges\":["
                + node("PIN", pinned, "Welcome back!", "") + ","
                + node("NEW", posted1, "Fall Social\\nFri Oct 31", ",\"carousel_media_count\":2,\"carousel_media\":["
                    + "{\"display_uri\":\"https://cdn.example/NEW.jpg\"},{\"image_versions2\":{\"candidates\":[{\"url\":\"https://cdn.example/NEW2.jpg\"}]}}]") + ","
                + node("OLD", posted2, "GBM", ",\"product_type\":\"clips\"")
                + "]}}}}}]]}</script></head><body></body></html>";
            List<Post> posts = Instagram.parsePage(page);
            check.same("all three", 3, posts.size());
            check.same("newest first, pinned or not", "NEW", posts.get(0).shortcode);
            check.same("pinned last", "PIN", posts.get(2).shortcode);
            check.same("time from the id", posted1, posts.get(0).postedAt);
            check.same("caption", "Fall Social\nFri Oct 31", posts.get(0).caption);
            check.same("carousel pictures", Arrays.asList("https://cdn.example/NEW.jpg", "https://cdn.example/NEW2.jpg"), posts.get(0).images);
            check.same("carousel count", 2, posts.get(0).carouselCount);
            check.same("post link", "https://www.instagram.com/p/NEW/", posts.get(0).url);
            check.same("reel link", "https://www.instagram.com/reel/OLD/", posts.get(1).url);
            check.ok("no posts in a login page", Instagram.parsePage("<html><a href=\"/accounts/login\">Log in</a></html>") == null);
        });

        check.run("profile api", () -> {
            String json = "{\"data\":{\"user\":{\"edge_owner_to_timeline_media\":{\"edges\":["
                + "{\"node\":{\"shortcode\":\"A\",\"taken_at_timestamp\":" + (posted2 / 1000) + ",\"display_url\":\"https://cdn.example/a.jpg\","
                + "\"edge_media_to_caption\":{\"edges\":[{\"node\":{\"text\":\"Old one\"}}]}}},"
                + "{\"node\":{\"shortcode\":\"B\",\"taken_at_timestamp\":" + (posted1 / 1000) + ",\"display_url\":\"https://cdn.example/b.jpg\","
                + "\"edge_media_to_caption\":{\"edges\":[]},\"edge_sidecar_to_children\":{\"edges\":["
                + "{\"node\":{\"display_url\":\"https://cdn.example/b.jpg\"}},{\"node\":{\"display_url\":\"https://cdn.example/b2.jpg\"}}]}}}"
                + "]}}},\"status\":\"ok\"}";
            List<Post> posts = Instagram.parseApi(json);
            check.same("newest first", "B", posts.get(0).shortcode);
            check.same("no caption", "", posts.get(0).caption);
            check.same("pictures", 2, posts.get(0).images.size());
            check.same("caption", "Old one", posts.get(1).caption);
            check.fails("a refusal", () -> Instagram.parseApi("{\"message\":\"Please wait\",\"status\":\"fail\"}"));
        });

        check.run("saved", () -> {
            Post post = new Post("X", "https://www.instagram.com/p/X/", posted1, "Hi \"there\"\n🎉", Arrays.asList("a", "b"), 2);
            Post back = Post.read(Post.write(Arrays.asList(post))).get(0);
            check.same("caption", post.caption, back.caption);
            check.same("time", post.postedAt, back.postedAt);
            check.same("images", post.images, back.images);
        });

        check.run("snowflakes", () -> {
            check.same("an id", posted1, Instagram.snowflake(pk(posted1)));
            check.same("an id with its owner", posted1, Instagram.snowflake(pk(posted1) + "_123"));
            check.same("not an id", 0L, Instagram.snowflake("abc"));
        });

        check.run("dates", () -> {
            String caption = "Join us for our Fall Social on Friday, Oct 31 at 6pm! RSVP by 10/29.";
            List<Dates.Mention> m = Dates.find(caption, posted1, PHOENIX);
            check.same("two mentions", 2, m.size());
            check.same("weekday, date and time as one", "Friday, Oct 31 at 6pm", caption.substring(m.get(0).start, m.get(0).end));
            check.same("its day", at("2026-10-31T00:00:00-07:00"), m.get(0).day);
            check.same("its time", 18 * 60, m.get(0).minutes);
            check.same("numeric", "10/29", caption.substring(m.get(1).start, m.get(1).end));

            String range = "GBM this Thursday 7-9pm in MU 202";
            m = Dates.find(range, posted2, PHOENIX);
            check.same("weekday and range", "Thursday 7-9pm", range.substring(m.get(0).start, m.get(0).end));
            check.same("the thursday after", at("2026-10-15T00:00:00-07:00"), m.get(0).day);
            check.same("starts at 7pm", 19 * 60, m.get(0).minutes);

            check.same("day month", at("2026-11-02T00:00:00-07:00"), Dates.find("on the 2nd of November", posted1, PHOENIX).get(0).day);
            check.same("into next year", at("2027-01-15T00:00:00-07:00"), Dates.find("see you Jan 15th", at("2026-12-01T12:00:00-07:00"), PHOENIX).get(0).day);
            check.same("6:30 PM", 18 * 60 + 30, Dates.find("doors 6:30 PM", posted1, PHOENIX).get(0).minutes);
            check.same("11-1pm", 11 * 60, Dates.find("11-1pm", posted1, PHOENIX).get(0).minutes);
            check.same("noon", 12 * 60, Dates.find("at noon", posted1, PHOENIX).get(0).minutes);
            check.same("no dates in plain words", 0, Dates.find("Thank you to everyone who came, we may do it again", posted1, PHOENIX).size());
            check.same("not a fraction of a price", 0, Dates.find("$5/10 people", posted1, PHOENIX).size());
            check.same("clock", "6:30 PM", Dates.clock(18 * 60 + 30));
            check.same("midnight", "12 AM", Dates.clock(0));
        });

        check.run("next event", () -> {
            Post social = new Post("S", "u", posted1, "Fall Social!\nFriday, Oct 31\n6-9 PM at the MU", Arrays.asList("a"), 0);
            Post gbm = new Post("G", "u", posted2, "GBM this Thursday at 7pm", Arrays.asList("a"), 0);
            Post recap = new Post("R", "u", posted2, "Thanks for coming on Oct 3!", Arrays.asList("a"), 0);
            long now = at("2026-10-23T09:00:00-07:00");
            Dates.Event next = Dates.next(Arrays.asList(social, gbm, recap), now, PHOENIX);
            check.same("the social", "S", next.post.shortcode);
            check.same("on the 31st", at("2026-10-31T00:00:00-07:00"), next.day);
            check.same("from a time on its own line", 18 * 60, next.minutes);
            check.same("8 days away", 8, Dates.daysBetween(Dates.midnight(now, PHOENIX), next.day));
            check.ok("nothing once it has passed", Dates.next(Arrays.asList(social, gbm, recap), at("2026-11-01T09:00:00-07:00"), PHOENIX) == null);
            Dates.Event today = Dates.next(Arrays.asList(gbm), at("2026-10-15T08:00:00-07:00"), PHOENIX);
            check.same("today counts", at("2026-10-15T00:00:00-07:00"), today.day);
        });

        check.run("words", () -> {
            long now = at("2026-10-23T09:00:00-07:00");
            check.same("minutes", "5 min ago", Dates.ago(now - 5 * 60000, now, PHOENIX));
            check.same("hours", "3 h ago", Dates.ago(now - 3 * 3600000, now, PHOENIX));
            check.same("days", "2 days ago", Dates.ago(posted1, now, PHOENIX));
            check.same("a date", "Oct 14", Dates.ago(posted2, now, PHOENIX));
            check.same("last year", "Oct 14, 2025", Dates.date(at("2025-10-14T09:00:00-07:00"), now, PHOENIX));
            Post social = new Post("S", "u", posted1, "Fall Social at the MU! Food and games.\nFriday, Oct 31 6pm", Arrays.asList("a"), 0);
            Dates.Event next = Dates.next(Arrays.asList(social), now, PHOENIX);
            check.same("when", "Sat 6 PM · in 8 days", Dates.when(next, now, PHOENIX));
            check.same("page", Arrays.asList("OCT", "31"), Arrays.asList(Dates.page(next.day, PHOENIX)));
            check.same("tomorrow", "Tomorrow · 6 PM", Dates.when(next, at("2026-10-30T20:00:00-07:00"), PHOENIX));
            check.same("title", "Fall Social at the MU!", Dates.title("\n  " + social.caption));
            check.same("short sentence kept whole", "Hi. We meet weekly", Dates.title("Hi. We meet weekly"));
        });

        if (!check.report()) System.exit(1);
    }
}
