package net.lobster.linking_minecarts.capability;

import java.util.UUID;

public class CartLinkData {

    private UUID leader;
    private UUID follower;

    public UUID getLeader()   { return leader; }
    public UUID getFollower() { return follower; }

    public void setLeader(UUID id)   { this.leader = id; }
    public void setFollower(UUID id) { this.follower = id; }

    public void clear() {
        leader = null;
        follower = null;
    }

    public boolean isFull() {
        return leader != null && follower != null;
    }
}