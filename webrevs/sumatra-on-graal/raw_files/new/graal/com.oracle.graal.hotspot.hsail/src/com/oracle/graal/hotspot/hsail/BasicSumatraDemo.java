package com.oracle.graal.hotspot.hsail;

import java.util.*;
import java.util.stream.*;

public class BasicSumatraDemo {

    public static void main(String[] args) {
        // System.out.println(System.getProperties().toString());
        int a[] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        int b[] = {9, 8, 7, 6, 5, 4, 3, 2, 1, 0};
        int c[] = new int[a.length];
        IntStream.range(0, a.length).parallel().forEach(id -> {
            c[id] = a[id] + b[id];
        });
        System.out.println(Arrays.toString(c));
    }
}
