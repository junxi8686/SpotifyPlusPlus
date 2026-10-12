package com.spotifyplusplus.lyrics.providers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.spotifyplusplus.testsupport.FakeAndroidContext;

import org.junit.Test;

/**
 * A recorded access loss hides cached lyrics; it never refuses a request.
 *
 * <p>Automatic acquisition is a fresh request, not a cached replay, so it is allowed to ask and
 * let the response decide - the other five sources are treated exactly that way. This used to be
 * untrue for SpicyLyrics.org on both ends: {@code beginRequest} returned null while a termination
 * stood for the current key, and {@code acceptValidatedResponse} then refused to clear the flag
 * unless the request had been an explicit recovery - and nothing in the module ever made one. One
 * refusal therefore silenced the source for automatic acquisition and for every later picker check
 * until the owner replaced the key.
 */
public class SpicyOrgAccessStateTest {

    private static FakeAndroidContext context() {
        return new FakeAndroidContext();
    }

    @Test public void recordedAccessLossDoesNotRefuseAFreshRequest() {
        FakeAndroidContext context = context();
        SpicyOrgAccessState.RequestTicket first = SpicyOrgAccessState.beginRequest(context, false);
        assertNotNull(first);
        assertTrue(SpicyOrgAccessState.terminate(context, first, "key_revoked"));
        assertTrue(SpicyOrgAccessState.isTerminated(context));

        assertNotNull("a terminated key must still be allowed to try",
                SpicyOrgAccessState.beginRequest(context, false));
        assertTrue(SpicyOrgAccessState.canAcquire(context));
    }

    @Test public void validatedResponseRestoresAccessWithoutAManualCheck() {
        FakeAndroidContext context = context();
        SpicyOrgAccessState.RequestTicket first = SpicyOrgAccessState.beginRequest(context, false);
        assertTrue(SpicyOrgAccessState.terminate(context, first, "application_suspended"));
        assertTrue(SpicyOrgAccessState.isTerminated(context));

        SpicyOrgAccessState.RequestTicket retry = SpicyOrgAccessState.beginRequest(context, false);
        assertTrue(SpicyOrgAccessState.acceptValidatedResponse(context, retry));
        assertFalse("the server answering with usable lyrics is the confirmation it waits for",
                SpicyOrgAccessState.isTerminated(context));
    }

    @Test public void aTicketFromBeforeTheTerminationCannotRestoreAccess() {
        FakeAndroidContext context = context();
        SpicyOrgAccessState.RequestTicket first = SpicyOrgAccessState.beginRequest(context, false);
        assertTrue(SpicyOrgAccessState.terminate(context, first, "user_suspended"));

        // The revision moved with the termination, so the response that was already in flight is
        // still refused: a request made under the old state cannot speak for the new one.
        assertFalse(SpicyOrgAccessState.acceptValidatedResponse(context, first));
        assertTrue(SpicyOrgAccessState.isTerminated(context));
    }

    @Test public void anUnterminatedResponseChangesNothing() {
        FakeAndroidContext context = context();
        SpicyOrgAccessState.RequestTicket ticket = SpicyOrgAccessState.beginRequest(context, false);
        assertTrue(SpicyOrgAccessState.acceptValidatedResponse(context, ticket));
        assertFalse(SpicyOrgAccessState.isTerminated(context));
    }
}
