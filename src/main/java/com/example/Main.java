package com.example;
import com.example.api.ElpriserAPI;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import java.time.LocalDate;
import java.util.stream.Collectors;


public class Main {
    public static void main(String[] args) {

        // command-line arguments
        String zoneArg = null;
        String dateArg = null;
        boolean sorted = false;
        int chargingHours = 0;
        boolean help = false;

        for(int i = 0; i < args.length; i++){
            switch (args[i]){
                case "--zone" -> zoneArg = args[i + 1];
                case "--date" -> dateArg = args[i + 1];
                case "--sorted" -> sorted = true;
                case "--charging" -> chargingHours = Integer.parseInt(args[i + 1].replace("h", ""));
                case "--help" -> help = true;
            }
        }

        if (help) {
            printHelp();
            return;
        }

        // add interactive prompt?
        if (zoneArg == null) {
            printHelp();
            return;
        }




        ElpriserAPI.Prisklass zone;
        try {
            zone = ElpriserAPI.Prisklass.valueOf(zoneArg.toUpperCase());
        } catch (Exception e) {
            System.out.println("Ogiltig zon: " + zoneArg);
            return;
        }

        LocalDate baseDate;
        if(dateArg != null){
            try {
                baseDate = LocalDate.parse(dateArg);
            } catch (Exception e){
                System.out.println("Ogiltigt datum: " + dateArg);
                return;
            }
        } else {
            baseDate = LocalDate.now();
        }


        // Get prices from api
        ElpriserAPI api = new ElpriserAPI();
        List<ElpriserAPI.Elpris> todayPrices = api.getPriser(baseDate, zone);
        List<ElpriserAPI.Elpris> tomorrowPrices = api.getPriser(baseDate.plusDays(1), zone);

        List<ElpriserAPI.Elpris> allPrices = new ArrayList<>(todayPrices);
        if(!tomorrowPrices.isEmpty()){
            allPrices.addAll(tomorrowPrices);
        }

        if(allPrices.isEmpty()){
            System.out.println("Inga priser tillgängliga");
            return;
        }

        // collect all prices by theiir starting hour.
        Map<Integer, List<ElpriserAPI.Elpris>> groupedByHour = allPrices.stream()
                .collect(Collectors.groupingBy(
                        p -> p.timeStart().getHour(),
                        TreeMap::new,
                        Collectors.toList()
                ));

        List<ElpriserAPI.Elpris> hourlyAverages = new ArrayList<>();
        for (var entry : groupedByHour.entrySet()) {
            List<ElpriserAPI.Elpris> hourPrices = entry.getValue();

            double avgSekPerKWh = hourPrices.stream()
                    .mapToDouble(ElpriserAPI.Elpris::sekPerKWh)
                    .average().orElse(0);

            ElpriserAPI.Elpris first = hourPrices.get(0);

            // adds a new object with the average price from all values within an hour
            hourlyAverages.add(new ElpriserAPI.Elpris(
                    avgSekPerKWh,
                    first.eurPerKWh(),
                    first.exr(),
                    first.timeStart(),
                    first.timeEnd()
            ));
        }

        // Format to swedish öre
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(new Locale("sv", "SE"));
        DecimalFormat oreFormat = new DecimalFormat("#0.00", symbols);
        oreFormat.setMaximumFractionDigits(2);
        oreFormat.setMinimumFractionDigits(2);
        oreFormat.setGroupingUsed(false);

        List<ElpriserAPI.Elpris> toPrint = sorted ? new ArrayList<>(allPrices) : allPrices;
        if (sorted) {
            toPrint.sort(Comparator.comparing(ElpriserAPI.Elpris::sekPerKWh)
                    .reversed()
                    .thenComparing(ElpriserAPI.Elpris::timeStart));
        }

        for (ElpriserAPI.Elpris price : toPrint) {
            String ore = oreFormat.format(price.sekPerKWh() * 100);
            int startHour = price.timeStart().getHour();
            int endHour = price.timeEnd().getHour();
            if (endHour == startHour) {
                endHour = (endHour + 1) % 24;
            }
            System.out.printf("%02d-%02d %s öre%n", startHour, endHour, ore);
        }


        double averageSekPerKWh = hourlyAverages.stream().mapToDouble(ElpriserAPI.Elpris::sekPerKWh)
                .average().orElse(0);
        System.out.printf("Medelpris: %s öre%n", oreFormat.format(averageSekPerKWh * 100));


        // least expensive hour (then earliest)
        ElpriserAPI.Elpris minPrice = hourlyAverages.stream().min(Comparator
                        .comparing(ElpriserAPI.Elpris::sekPerKWh)
                        .thenComparing(ElpriserAPI.Elpris::timeStart))
                .orElse(null);

        if (minPrice != null) {
            String ore = oreFormat.format(minPrice.sekPerKWh() * 100);
            int startHour = minPrice.timeStart().getHour();
            int endHour = minPrice.timeEnd().getHour();
            if (endHour == startHour) {
                endHour = (endHour + 1) % 24;
            }
            System.out.printf("Lägsta pris: %s öre (%02d-%02d)%n", ore, startHour, endHour);
        }


        // most expensive hour (then earliest)
        ElpriserAPI.Elpris maxPrice = hourlyAverages.stream()
                .max(Comparator.comparing(ElpriserAPI.Elpris::sekPerKWh)
                .thenComparing(ElpriserAPI.Elpris::timeStart))
                .orElse(null);

        if (maxPrice != null) {
            String ore = oreFormat.format(maxPrice.sekPerKWh() * 100);
            int startHour = maxPrice.timeStart().getHour();
            int endHour = maxPrice.timeEnd().getHour();
            if (endHour == startHour) {
                endHour = (endHour + 1) % 24;
            }
            System.out.printf("Högsta pris: %s öre (%02d-%02d)%n", ore, startHour, endHour);
        }



        // sliding window
        if(chargingHours > 0){
            int windowSize = chargingHours; // 2, 4 or 8
            double bestSum = Double.MAX_VALUE;
            int bestStartIndex = -1;

            for(int i = 0; i <= hourlyAverages.size() - windowSize; i++){
                double currentSum = 0;

                for(int j = i; j < i + windowSize; j++){
                    currentSum += allPrices.get(j).sekPerKWh();
                }

                if(currentSum < bestSum){
                    bestSum = currentSum;
                    bestStartIndex = i;
                }
            }

            if(bestStartIndex != -1){
                double average = bestSum / windowSize;

                ElpriserAPI.Elpris startHour = allPrices.get(bestStartIndex);
                ElpriserAPI.Elpris endHour = allPrices.get(bestStartIndex + windowSize -1);

                System.out.printf("Påbörja laddning kl %s%n", startHour.timeStart().toLocalTime());
                System.out.printf("Medelpris för fönster: %s öre%n", oreFormat.format(average * 100));
                System.out.printf("Fönster: %02d-%02d%n",
                        startHour.timeStart().getHour(),
                        endHour.timeEnd().getHour());

            }
        }
    }
    // help
    private static void printHelp(){
        System.out.println("Usage: java -cp target/classes com.example.Main --zone SE1|SE2|SE3|SE4 [options]");
        System.out.println("Options:");
        System.out.println("  --date YYYY-MM-DD   Ange datum (default = idag)");
        System.out.println("  --sorted            Visa priser i stigande ordning");
        System.out.println("  --charging 2h|4h|8h Hitta optimalt laddfönster");
        System.out.println("  --help              Visa denna hjälptext");
    }
}
