package com.leon.workflow.messaging;

/** Binds model calls within one background reply or context operation to its persisted initiator. */
final class AgentUsageContext {
    private static final ThreadLocal<String> USER=new ThreadLocal<>();

    private AgentUsageContext(){}
    static String user(){return USER.get();}

    static void run(String user,Runnable work){
        String previous=USER.get();
        try{
            USER.set(user);
            work.run();
        }finally{
            if(previous==null)USER.remove();else USER.set(previous);
        }
    }
}
