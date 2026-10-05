package com.spotifyplusplus.lyrics.providers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.spotifyplusplus.SpotifyTrack;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

/**
 * The query plan is scored as a whole: a spelling that returns an adequate hit no longer ends the
 * search, so a later spelling still gets the chance to return the right recording.
 *
 * <p>This is the behaviour that was missing. Before it, the first query whose response held any
 * accepted candidate was fetched immediately, which is how a wrong recording - something that
 * merely cleared the threshold on a looser spelling - got read while a better spelling sat unasked
 * later in the same plan.
 */
public class QueryPlanBestCandidateTest {

    private static SpotifyTrack track(String title, String artist, String album) {
        return new SpotifyTrack(title, artist, album, "spotify:track:test", 0L, "", 0L, null,
                200000L, false);
    }

    private static NeteaseSongRanker.Candidate netease(SpotifyTrack query, String title,
                                                       String artist) {
        return new NeteaseSongRanker.Candidate(1L, title, Collections.singletonList(artist),
                TrackMatchScorer.score(new TrackMatchScorer.Target(query.title, query.artist,
                        query.album, query.duration), title,
                        Collections.singletonList(artist), query.album, query.duration),
                200000L, 0L);
    }

    @Test
    public void laterSpellingCanOutrankAnEarlierAdequateOne() {
        SpotifyTrack query = track("Take Me Hand", "Cecile Corbel", "Take Me Hand");
        // An adequate but wrong-scripted hit, and the exact one that a later spelling returns.
        NeteaseSongRanker.Candidate adequate = netease(query, "Take Me Hand (Live)", "Some Cover");
        NeteaseSongRanker.Candidate exact = netease(query, "Take Me Hand", "Cecile Corbel");

        List<NeteaseSongRanker.Candidate> carried =
                Collections.singletonList(adequate);
        List<NeteaseSongRanker.Candidate> fresh = Collections.singletonList(exact);

        List<NeteaseSongRanker.Candidate> best = NeteaseAdapter.betterOf(carried, fresh);
        assertSame("the later, better hit must win", fresh, best);
        assertTrue("and the walk must recognise it can stop",
                NeteaseAdapter.settled(best));
    }

    @Test
    public void earlierHitIsKeptWhenTheLaterSpellingIsWorse() {
        SpotifyTrack query = track("Take Me Hand", "Cecile Corbel", "Take Me Hand");
        NeteaseSongRanker.Candidate exact = netease(query, "Take Me Hand", "Cecile Corbel");
        NeteaseSongRanker.Candidate poor = netease(query, "Take Me Hand (Live)", "Some Cover");

        List<NeteaseSongRanker.Candidate> best = NeteaseAdapter.betterOf(
                Collections.singletonList(exact), Collections.singletonList(poor));
        assertTrue("the better hit already held must survive", best.get(0) == exact);
    }

    @Test
    public void anEmptyResponseNeverDiscardsWhatWasAlreadyFound() {
        SpotifyTrack query = track("Song", "Artist", "Album");
        List<NeteaseSongRanker.Candidate> carried =
                Collections.singletonList(netease(query, "Song", "Artist"));

        assertSame(carried, NeteaseAdapter.betterOf(carried,
                Collections.<NeteaseSongRanker.Candidate>emptyList()));
        assertSame(carried, NeteaseAdapter.betterOf(carried, null));
        // Nothing carried and nothing fresh stays nothing, so the caller reports an empty source.
        assertTrue(NeteaseAdapter.betterOf(null,
                Collections.<NeteaseSongRanker.Candidate>emptyList()) == null);
    }

    @Test
    public void anAdequateHitIsNotTreatedAsSettled() {
        SpotifyTrack query = track("Take Me Hand", "Cecile Corbel", "Take Me Hand");
        NeteaseSongRanker.Candidate loose = netease(query, "Take Me Hand (Live)", "Some Cover");
        assertFalse("a merely adequate match must keep the walk going",
                NeteaseAdapter.settled(Collections.singletonList(loose)));
        assertFalse(NeteaseAdapter.settled(null));
        assertFalse(NeteaseAdapter.settled(Collections.<NeteaseSongRanker.Candidate>emptyList()));
    }

    @Test
    public void qqMusicAppliesTheSamePolicy() {
        SpotifyTrack query = track("Take Me Hand", "Cecile Corbel", "Take Me Hand");
        QqSongRanker.Candidate adequate = new QqSongRanker.Candidate("mid-a", 1L,
                "Take Me Hand (Live)", Collections.singletonList("Some Cover"),
                TrackMatchScorer.score(new TrackMatchScorer.Target(query.title, query.artist,
                        query.album, query.duration), "Take Me Hand (Live)",
                        Collections.singletonList("Some Cover"), query.album, query.duration),
                200000L, 0L);
        QqSongRanker.Candidate exact = new QqSongRanker.Candidate("mid-b", 2L, "Take Me Hand",
                Collections.singletonList("Cecile Corbel"),
                TrackMatchScorer.score(new TrackMatchScorer.Target(query.title, query.artist,
                        query.album, query.duration), "Take Me Hand",
                        Collections.singletonList("Cecile Corbel"), query.album, query.duration),
                200000L, 0L);

        List<QqSongRanker.Candidate> best = QqMusicAdapter.betterOf(
                Collections.singletonList(adequate), Collections.singletonList(exact));
        assertTrue(best.get(0) == exact);
        assertTrue(QqMusicAdapter.settled(best));
    }
}
