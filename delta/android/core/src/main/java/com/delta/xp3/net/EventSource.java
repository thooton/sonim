package com.delta.xp3.net;

import com.xp3.common.Http;
import java.io.IOException;

/**
 * Where a session's event stream comes from: the cloud for its sessions, the
 * command center for its backup ones. Both speak the same stream.
 */
public interface EventSource {
    /** The stream after `afterSequence`; zero replays the whole session. */
    Http.Stream open(String session, long afterSequence) throws IOException, InterruptedException;
}
