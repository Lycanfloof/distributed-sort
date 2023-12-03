module AppInterface
{
    sequence<string> seqStr;

    ["java:type:java.util.ArrayList<String>"]
    sequence<string> ArrayList;

    dictionary<string, ArrayList> dictStrSeq;

    ["java:implements:java.lang.Runnable"]
    class Task {
        seqStr data;
    };

    ["java:implements:java.lang.Runnable"]
    class GroupingTask extends Task {
        dictStrSeq groups;
        int characters;
    };

    interface Worker
    {
        void launch();
        void shutdown();
    };

    interface Master
    {
        void signUp(string id, Worker* worker);
        Task getTask(string id);
        void addPartialResults(seqStr array);
        void addGroupingResults(dictStrSeq groups);
    };
};