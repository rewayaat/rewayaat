package com.rewayaat.loader.oldhdp;

public class OldHDPLoader {

    public static void main(String[] args) {
        Thread t = new OldHDPWorker(2, 1000);
        t.start();
        Thread a = new OldHDPWorker(1001, 2010);
        a.start();
        Thread d = new OldHDPWorker(2011, 4000);
        d.start();
        Thread g = new OldHDPWorker(4001, 6000);
        g.start();
        Thread h = new OldHDPWorker(6001, 8000);
        h.start();
        Thread j = new OldHDPWorker(8001, 10000);
        j.start();
        Thread k = new OldHDPWorker(10001, 12000);
        k.start();
        Thread m = new OldHDPWorker(12001, 14000);
        m.start();
        Thread n = new OldHDPWorker(14001, 15708);
        n.start();
    }
}
