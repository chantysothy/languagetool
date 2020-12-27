/* LanguageTool, a natural language style checker
 * Copyright (C) 2020 Daniel Naber (http://www.danielnaber.de)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package org.languagetool.dev.simulation;

import org.apache.commons.lang3.StringUtils;
import org.languagetool.tools.StringTools;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Sends requests to a local LT server, simulating a real user of the browser add-on or languagetool.org.
 * TODO: use several threads; make input data more real, especially for document length
 */
class TypingSimulator {

  private static final String apiUrl = "http://localhost:8081/v2/check";
  //private static final String apiUrl = "https://api.languagetool.org/v2/check";
  private static final int warmUpChecks = 20;        // checks at start-up not to be considered for calculation of average values
  private static final float copyPasteProb = 0.05f;  // per document
  private static final float backSpaceProb = 0.05f;  // per character
  private static final float typoProb = 0.03f;       // per character
  private static final int minWaitMillis = 0;        // more real: 10
  private static final int avgWaitMillis = 10;       // more real: 100   // don't set to 0, will cause endless loop
  private static final int checkAtMostEveryMillis = 10;  // more real: 1500

  private final Random rnd = new Random(123);

  private long totalTime = 0;
  private long totalChecks = 0;
  private long totalChecksSkipped = 0;

  public static void main(String[] args) throws IOException {
    if (args.length != 1) {
      System.out.println("Usage: " + TypingSimulator.class.getSimpleName() + " <input>");
      System.exit(1);
    }
    if (avgWaitMillis < 2) {
      throw new RuntimeException("Set avgWaitMillis to > 1");
    }
    List<String> lines = Files.readAllLines(Paths.get(args[0]));
    //List<String> lines = Arrays.asList("Das hier ist ein Test"/*, "Hier kommt das zweite Dokument."*/);
    new TypingSimulator().run(lines);
  }

  private void run(List<String> docs) {
    System.out.println("Using API at " + apiUrl);
    List<Long> totalTimes = new ArrayList<>();
    List<Float> avgTimes = new ArrayList<>();
    int maxRuns = 3;  // keep at 3, the chart library needs 3 values for the error bars
    for (int i = 0; i < maxRuns; i++) {
      System.out.println("=== Run " + (i+1) + " of " + maxRuns + " =====================");
      totalChecks = 0;
      totalTime = 0;
      totalChecksSkipped = 0;
      for (String doc : docs) {
        runOnDoc(doc);
      }
      totalTimes.add(totalTime);
      float avg = (float) totalTime / (float) totalChecks;
      avgTimes.add(avg);
    }
    totalTimes.sort(Long::compareTo);
    avgTimes.sort(Float::compareTo);
    System.out.println("Total times: " + totalTimes + " ms");
    System.out.printf(Locale.ENGLISH, "Avg. times per doc: %s ms\n", avgTimes);
    String date = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date());
    String totalTimesStr = totalTimes.stream().map(k -> k.toString()).collect(Collectors.joining(";"));
    String avgTimesStr = avgTimes.stream().map(k -> k.toString()).collect(Collectors.joining(";"));
    System.out.printf(Locale.ENGLISH, "CSV: %s,%s,%s\n", date, totalTimesStr, avgTimesStr);  // so results can easily be grepped into a CSV
  }

  @SuppressWarnings("BusyWait")
  private void runOnDoc(String doc) {
    if (rnd.nextFloat() < copyPasteProb) {
      check(doc);
    } else {
      long lastCheck = 0;
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < doc.length(); i++) {
        //System.out.println(i + ". " + doc.charAt(i));
        if (rnd.nextFloat() < typoProb) {
          if (rnd.nextBoolean()) {
            sb.append("x");  // simulate randomly inserted char
          } else if (sb.length() > 0) {
            sb.replace(sb.length()-1, sb.length(), "");  // simulate random char left out
          }
        }
        if (rnd.nextFloat() < backSpaceProb && i > 2) {
          sb.replace(sb.length()-1, sb.length(), "");
          check(sb.toString());
          i -= 2;
        } else {
          char c = doc.charAt(i);
          sb.append(c);
        }
        long millisSinceLastCheck = System.currentTimeMillis() - lastCheck;
        if (millisSinceLastCheck > checkAtMostEveryMillis || i == doc.length()-1) {
          check(sb.toString());
          lastCheck = System.currentTimeMillis();
        }
        try {
          int waitMillis;
          do {
            double val = rnd.nextGaussian() * avgWaitMillis + avgWaitMillis;
            waitMillis = (int) Math.round(val);
          } while (waitMillis <= 0);
          //System.out.println("waiting " + waitMillis);
          Thread.sleep(minWaitMillis + waitMillis);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    }
    System.out.println();
  }

  private void check(String doc) {
    try {
      checkByPOST(doc, "textLevelOnly");
      checkByPOST(doc, "allButTextLevelOnly");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void checkByPOST(String text, String mode) throws IOException {
    long runTimeStart = System.currentTimeMillis();
    String postData =
            "&mode=" + mode +
            "&text=" + URLEncoder.encode(text, "UTF-8") +
            "&textSessionId=10914:1608926270970" +
            "&enableHiddenRules=true" +
            "&motherTongue=de" +
            "&language=auto" +
            "&noopLanguages=de,en" +
            "&preferredLanguages=de,en" +
            "&preferredVariants=en-US,de-DE,pt-BR,ca-ES" +
            "&disabledRules=WHITESPACE_RULE" +
            "&useragent=performance-test";
    URL url = new URL(apiUrl +
            "?instanceId=10914%3A1608926270970" +
            "&c=1" +
            "&v=0.0.0");
    //System.out.println("Sending to " + apiUrl + ", " + mode + ": " + text);
    try {
      Map<String, String> map = new HashMap<>();
      checkAtUrlByPost(url, postData, map);
      long runTime = System.currentTimeMillis() - runTimeStart;
      System.out.printf("%sms %s: %s\n", String.format("%1$5d", runTime), String.format("%1$20s", mode), text);
      //System.out.println("Checking " + text.length() + " chars took " + runTime + "ms");
      if (totalChecksSkipped < warmUpChecks) {
        System.out.println("Warm-up, ignoring result...");
        totalChecksSkipped++;
      } else {
        totalChecks++;
        totalTime += runTime;
      }
    } catch (IOException e) {
      System.err.println("Got error from " + url + " (" + text.length() + " chars): "
              + e.getMessage() + ", text was (" + text.length() +  " chars): '" + StringUtils.abbreviate(text, 100) + "'");
      e.printStackTrace();
    }
  }

  @SuppressWarnings("UnusedReturnValue")
  private String checkAtUrlByPost(URL url, String postData, Map<String, String> properties) throws IOException {
    String keepAlive = System.getProperty("http.keepAlive");
    try {
      System.setProperty("http.keepAlive", "false");  // without this, there's an overhead of about 1 second - not sure why
      URLConnection connection = url.openConnection();
      for (Map.Entry<String, String> entry : properties.entrySet()) {
        connection.setRequestProperty(entry.getKey(), entry.getValue());
      }
      connection.setDoOutput(true);
      try (Writer writer = new OutputStreamWriter(connection.getOutputStream(), UTF_8)) {
        writer.write(postData);
        writer.flush();
        return StringTools.streamToString(connection.getInputStream(), "UTF-8");
      }
    } finally {
      if (keepAlive != null) {
        System.setProperty("http.keepAlive", keepAlive);
      }
    }
  }

}