package cp26.mining.examples.tools;

import java.io.*;
import java.util.*;

public class UPIToUMI {

    static class Pattern {
        Set<Integer> items;
        long utility;

        Pattern(Set<Integer> items, long utility) {
            this.items = items;
            this.utility = utility;
        }

        boolean isSubsetOf(Pattern other) {
            return other.items.containsAll(items) && other.items.size() > items.size();
        }

        boolean isSupersetOf(Pattern other) {
            return items.containsAll(other.items) && items.size() > other.items.size();
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (int i : items) {
                sb.append(i).append(" ");
            }
            sb.append("#UTIL: ").append(utility);
            return sb.toString();
        }
    }

    public static void main(String[] args) throws Exception {

        if (args.length < 2) {
            System.out.println("Usage: java UPIToUMI inputUPI.txt outputUMI.txt");
            return;
        }

        String input = args[0];
        String output = args[1];

        List<Pattern> patterns = readPatterns(input);

        List<Pattern> umi = new ArrayList<>();

        for (Pattern p : patterns) {

            boolean dominated = false;

            for (Pattern q : patterns) {

                if (p == q) continue;

                // subset dominance
                if (q.isSubsetOf(p) && q.utility >= p.utility) {
                    dominated = true;
                    break;
                }

                // superset dominance
                if (q.isSupersetOf(p) && q.utility >= p.utility) {
                    dominated = true;
                    break;
                }
            }

            if (!dominated) {
                umi.add(p);
            }
        }

        writePatterns(output, umi);

        System.out.println("UPI patterns : " + patterns.size());
        System.out.println("UMI patterns : " + umi.size());

        System.out.println("RESULT\talgorithm=UPIToUMI\tupi=" +
                patterns.size() + "\tumi=" + umi.size());
    }

    static List<Pattern> readPatterns(String file) throws Exception {

        List<Pattern> patterns = new ArrayList<>();

        BufferedReader br = new BufferedReader(new FileReader(file));

        String line;

        while ((line = br.readLine()) != null) {

            line = line.trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split("#UTIL:");

            String[] itemsStr = parts[0].trim().split(" ");

            Set<Integer> items = new HashSet<>();

            for (String s : itemsStr) {
                if (!s.isEmpty())
                    items.add(Integer.parseInt(s));
            }

            long util = Long.parseLong(parts[1].trim());

            patterns.add(new Pattern(items, util));
        }

        br.close();

        return patterns;
    }

    static void writePatterns(String file, List<Pattern> patterns) throws Exception {

        PrintWriter pw = new PrintWriter(new FileWriter(file));

        for (Pattern p : patterns) {
            pw.println(p.toString());
        }

        pw.close();
    }
}
