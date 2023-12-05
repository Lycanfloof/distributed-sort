import AppInterface.GroupingTask;
import AppInterface.MasterPrx;
import AppInterface.Task;
import com.jcraft.jsch.*;
import com.zeroc.Ice.Current;
import sorter.MSDRadixSortTask;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class WorkerI extends ThreadPoolExecutor implements AppInterface.Worker {
    private final MasterPrx masterPrx;
    private final String masterTemporalPath;
    private final String workerHost;
    private final Session session;

    private boolean isRunning;

    private ForkJoinPool fjPool;

    public WorkerI(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
                   BlockingQueue<Runnable> workQueue, MasterPrx masterPrx, String masterHost,
                   String masterTemporalPath, String workerHost, String username, String password) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue);
        this.masterPrx = masterPrx;
        this.masterTemporalPath = masterTemporalPath;
        this.workerHost = workerHost;
        isRunning = false;
        this.fjPool = new ForkJoinPool(maximumPoolSize);

        try { session = createSession(username, password, masterHost); } catch (JSchException e) {
            throw new RuntimeException(e);
        }
    }

    private Session createSession(String username, String password, String masterHost) throws JSchException {
        System.out.println("Creating SSH session with master at " + username + "@"+ masterHost);
        long t1 = System.currentTimeMillis();
        Session session = new JSch().getSession(username, masterHost, 22);
        session.setPassword(password);
        session.setConfig("StrictHostKeyChecking", "no");
        long t2 = System.currentTimeMillis();
        System.out.println("Created SSH session with master (" + (t2-t1) + " ms)");
        return session;
    }

    @Override
    public void launch(Current current) {
        isRunning = true;
        new Thread(this::startTaskPolling).start();

        System.out.println("Connecting to master...");
        long t1 = System.currentTimeMillis();
        try
        {
            session.connect();
            long t2 = System.currentTimeMillis();
            System.out.println("Connected to master (" + (t2-t1) + " ms)");
        }
        catch (JSchException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public void ping(Current current) {}

    private void startTaskPolling() {
        while (isRunning) {
            getThenExecuteTask();
        }
    }

    private void getThenExecuteTask() {
        Task task = masterPrx.getTask(workerHost);
        if (task != null) {
            List<String> list = readFile(task.key);
            if (task instanceof GroupingTask) { doMultipleGroupingTasks(list, (GroupingTask) task); }
            else {
                taskForSorting(list, task);
            }
        }
    }

    private List<String> readFile(String fileName) {
        List<String> list = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader("./temp/" + fileName))) {
            String line = br.readLine();
            while(line != null) {
                list.add(line);
                line = br.readLine();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return list;
    }

    private void doMultipleGroupingTasks(List<String> list, GroupingTask task) {
        List<RunnableFuture<Void>> groupingTasks = new ArrayList<>();
        for (long i = 0; i < task.step; i++) {
            String finalI = String.valueOf(i);
            Runnable groupTask = () -> taskForGrouping(list, task, finalI);
            groupingTasks.add(new FutureTask<>(groupTask,null));
            execute(groupTask);
        }
        for (RunnableFuture<Void> r: groupingTasks) {
            try { r.get(); } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void taskForGrouping(List<String> list, GroupingTask task, String fileName) {
        System.out.println("Grouping Task Received.");

        Map<String, List<String>> groups = separateListIntoGroups(list, task.keyLength);
        groups.forEach((key, groupList) -> createFileForGroupAndSendToMaster(fileName, key, groupList));

        masterPrx.addGroupingResults(workerHost, task.key);
    }

    private Map<String, List<String>> separateListIntoGroups(List<String> list, int keyLength) {
        Map<String, List<String>> groups = new HashMap<>();

        for (String string : list) {
            String key = string.substring(0, keyLength);
            if (!groups.containsKey(key)) { groups.put(key, new ArrayList<>()); }
            groups.get(key).add(string);
        }

        return groups;
    }

    private void createFileForGroupAndSendToMaster(String taskKey, String key, List<String> groupList) {
        try {
            String groupFileName = getGroupFileName(key) + taskKey;
            createFile(groupFileName, groupList);
            sendFileToMaster("./temp/" + groupFileName, masterTemporalPath);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String getGroupFileName(String key) {
        StringBuilder groupFileName = new StringBuilder();

        for (int i = 0; i < key.length(); i++) {
            int character = key.charAt(i);
            groupFileName.append(character).append("_");
        }

        return groupFileName.toString();
    }

    private void createFile(String fileName, List<String> data) throws IOException {
        String filePath = "./temp/" + fileName;

        checkFileRestrictions(new File(filePath));

        try (BufferedWriter bw = new BufferedWriter(new FileWriter(filePath))) {
            for (String line : data) {
                bw.write(line);
                bw.newLine();
            }
        }
    }

    private void checkFileRestrictions(File file) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException("The file already exists and it couldn't be deleted.");
        }
        if (!file.createNewFile()) {
            throw new IOException("The file couldn't be created.");
        }
    }

    private void sendFileToMaster(String from, String to) {
        try {
            File localFile = new File(from);
            ChannelSftp channelSftp = (ChannelSftp) session.openChannel("sftp");
            channelSftp.connect();
            channelSftp.cd(to);
            channelSftp.put(new FileInputStream(localFile), localFile.getName());
            channelSftp.disconnect();
        } catch (JSchException | SftpException | FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private void taskForSorting(List<String> list, Task task) {
        //TODO. Parallel sorting.

        String[] listAsArray = new String[list.size()];
        list = new ArrayList<>(); //to clear memory
        fjPool.invoke(new MSDRadixSortTask(list.toArray(listAsArray))); //"inplace"
        list = Arrays.asList(listAsArray);

        //list.sort(Comparator.naturalOrder());
        try {
            createFile(task.key, list); //The FileName has been formatted from Master, hence why we use 'task.key'.
            sendFileToMaster("./temp/" + task.key, masterTemporalPath);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        masterPrx.addSortingResults(workerHost, task.key);
    }

    @Override
    public void shutdown(Current current) {
        isRunning = false;
        shutdown();
        session.disconnect();
        removeTemporalFiles();
    }

    private void removeTemporalFiles() {
        for (File file : Objects.requireNonNull(new File("./temp/").listFiles())) {
            boolean notDeleted = !file.isDirectory() && !file.delete();
            if (notDeleted) { System.out.println("Couldn't delete file " + file + "."); }
        }
    }
}