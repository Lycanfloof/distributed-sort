import AppInterface.GroupingTask;
import AppInterface.MasterPrx;
import AppInterface.Task;
import com.zeroc.Ice.Current;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class WorkerI extends ThreadPoolExecutor implements AppInterface.Worker {
    private final MasterPrx masterPrx;
    private final String id;
    private boolean isRunning;

    public WorkerI(int corePoolSize, int maximumPoolSize, long keepAliveTime,
                   TimeUnit unit, BlockingQueue<Runnable> workQueue, MasterPrx masterPrx, String id) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue);
        this.masterPrx = masterPrx;
        this.id = id;
        isRunning = false;
    }

    @Override
    public void launch(Current current) {
        isRunning = true;
        Thread thread = new Thread(this::startTaskPolling);
        thread.start();
    }

    @Override
    public void ping(Current current) {}

    private void startTaskPolling() {
        while (isRunning) {
            if (getActiveCount() < getMaximumPoolSize()) { getThenExecuteTask(); }
        }
    }

    private void getThenExecuteTask() {
        Task task = masterPrx.getTask(id);
        List<String> list = readFile(task.id);
        if (task != null) {
            if (task instanceof GroupingTask) {
                GroupingTask groupingTask = (GroupingTask) task;
                execute(() -> {
                    for (String string : list) {
                        String key = string.substring(0, groupingTask.characters);
                        if (!groupingTask.groups.containsKey(key)) {
                            groupingTask.groups.put(key, new ArrayList<>());
                        }
                        groupingTask.groups.get(key).add(string);
                    }
                    // TODO: Enviar el archivo de nuevo al Master usando FTP Client
                    sendFileToMaster(groupingTask.id);
                    masterPrx.addGroupingResults(id, groupingTask.id);
                });
            } else {
                // TODO: Enviar el archivo de nuevo al Master usando FTP Client
                sendFileToMaster(task.id);
                execute(() -> {
                    list.sort(Comparator.naturalOrder());
                    masterPrx.addSortingResults(id, task.id);
                });
            }
        }
    }

    // Método para enviar el archivo al Master usando FTP Client
    private void sendFileToMaster(String fileId) throws IOException {
        try (Socket sock = new Socket("127.0.0.1", 13267);
             OutputStream os = sock.getOutputStream()) {
            new FileClient().send(os, fileId);
        }
    }
    private List<String> readFile(String fileName) {
        List<String> list = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader("/temp/" + fileName))) {
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

    @Override
    public void shutdown(Current current) {
        isRunning = false;
        shutdown();
    }
}
