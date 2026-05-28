package com.edgefabric.caching.membership;

import com.edgefabric.caching.model.NodeInfo;

public class MembershipMerger {
    public boolean shouldAccept(NodeInfo incoming, NodeInfo existing){
        int incCompare = Long.compare(
                incoming.getIncarnation(),
                existing.getIncarnation()
        );

        if(incCompare > 0) return true;
        if(incCompare < 0) return false;

        if(incoming.getStatus().severity() > existing.getStatus().severity()){
            return true;
        }

        return incoming.getStatus() == existing.getStatus() && incoming.getHeartbeat() > existing.getHeartbeat();
    }
}
