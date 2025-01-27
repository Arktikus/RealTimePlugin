package de.rexlnico.realtimeplugin.methodes;

import de.rexlnico.realtimeplugin.main.Main;
import de.rexlnico.realtimeplugin.util.Utils;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

public class WorldContainer {

    private static final double SECONDS_TO_TICKS_FACTOR = 1_000d / Math.pow(60d, 2d);

    private final File file;

    private BukkitTask task;
    private boolean doDaylightCycle; // save original value of doDaylightCycle


    private boolean active;
    private World world;
    private long updateInterval;
    public boolean time;
    public boolean weather;
    private String weatherKey;
    private String timezone;
    private ZoneId zone;
    private String[] weatherLocation;

    public WorldContainer(File file) {
        this.file = file;
        if (!this.file.exists()) {
            throw new IllegalArgumentException(String.format("World file %s does not exist.", this.file));
        }

        update();
    }

    public void runUpdate() {
        BukkitScheduler scheduler = Bukkit.getScheduler();
        Main plugin = Main.getPlugin();

        cancelTask();

        if (active && world != null && (weather || time)) {
            List<Runnable> stack = new ArrayList<>();

            if (time) {
                //this.doDaylightCycle = world.getGameRuleValue("doDaylightCycle");
                //world.setGameRuleValue("doDaylightCycle", "false");
                this.doDaylightCycle = Boolean.TRUE.equals(world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE));
                world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);

                stack.add(() -> {
                    // get time asynchronously
                    int time = getTime();
                    // set time synchronously
                    scheduler.runTask(plugin, () -> world.setTime(time));
                });
            }
            if (weather) {
                stack.add(() -> {
                    // get weather asynchronously
                    String weather = fetchWeather();
                    // set weather synchronously
                    scheduler.runTask(plugin, () -> setWeather(weather));
                });
            }

            task = Bukkit.getScheduler().runTaskTimerAsynchronously(Main.getPlugin(),
                    () -> stack.forEach(Runnable::run), 0, 20 * updateInterval);
        }
    }

    public ZonedDateTime getDateTime(){
        return ZonedDateTime.ofInstant(Instant.now() /* in UTC */, zone);
    }

    public int getTime() {
        ZonedDateTime dateTime = getDateTime();
        int secondsInDay = dateTime.getHour() * 3600 + dateTime.getMinute() * 60 + dateTime.getSecond();
        return Utils.overflow(18_000 + (int) (secondsInDay * SECONDS_TO_TICKS_FACTOR), 24_000);
    }

    public void disable() {
        if (time) {
            //world.setGameRuleValue("doDaylightCycle", doDaylightCycle);
            world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, doDaylightCycle);
        }
        cancelTask();
    }

    public void update() {

        // load JSON data
        JSONParser parser = new JSONParser();
        if(!file.exists()){
            cancelTask();
            Main.getWorldManager().unloadWorldContainer(this);
            return;
        }
        try (Reader reader = new FileReader(file.getPath())) {
            JSONObject object = (JSONObject) parser.parse(reader);

            String worldS = (String) object.get("world");
            World world = Bukkit.getWorld(worldS);
            if (world != null) {
                this.world = world;
                this.active = (boolean) object.get("active");
                this.updateInterval = Math.max(10, (long) object.get("updateInterval"));
                JSONObject timeObject = (JSONObject) object.get("time");
                this.time = (boolean) timeObject.get("active");
                this.timezone = (String) timeObject.get("timezone");
                zone = ZoneId.of(timezone);
                JSONObject weatherObject = (JSONObject) object.get("weather");
                this.weather = (boolean) weatherObject.get("active");
                this.weatherKey = (String) weatherObject.get("weatherKey");
                this.weatherLocation = new String[]{((String) weatherObject.get("City")).replace(" ", "%20"), ((String) weatherObject.get("Country")).replace(" ", "%20")};
            }
        } catch (Exception e) {
            throw new RuntimeException(String.format("Count not load world configuration %s", this.file), e);
        }

        runUpdate();
    }


    private void cancelTask() {
        if (task != null) {
            BukkitScheduler scheduler = Bukkit.getScheduler();
            int tid = task.getTaskId();
            if (scheduler.isCurrentlyRunning(tid) || scheduler.isQueued(tid)) {
                task.cancel();
                task = null;
            }
        }
    }

    public String fetchWeather() {
        try {
            if (weatherKey.isEmpty()) {
                return "sun";
            }
            final String search = weatherLocation[0] + "," + weatherLocation[1];
            final String url = "http://api.openweathermap.org/data/2.5/weather?q=" + search + "&APPID=" + weatherKey;

            byte[] response = Utils.httpRequest(url);
            if (response == null) {
                return "sun";
            }

            final JSONObject object = Utils.parseJSON(new String(response, StandardCharsets.UTF_8));
            return ((String) ((JSONObject) ((JSONArray) object.get("weather")).get(0)).get("main")).toLowerCase();
        } catch (Exception e) {
            return "sun";
        }
    }

    public void setWeather(String weather) {
        switch (weather) {
            case "rain":
                try {
                    world.setStorm(true);
                    world.setWeatherDuration(Integer.MAX_VALUE);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                break;
            case "thunderstorm":
                try {
                    world.setStorm(true);
                    world.setWeatherDuration(Integer.MAX_VALUE);
                    world.setThundering(true);
                    world.setThunderDuration(Integer.MAX_VALUE);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                break;
            default:
                try {
                    world.setStorm(false);
                    world.setWeatherDuration(0);
                    world.setThundering(false);
                    world.setThunderDuration(0);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                break;
        }
    }

    public World getWorld() {
        return world;
    }

    public String[] getWeatherLocation() {
        return weatherLocation;
    }

    public String getWeatherKey() {
        return weatherKey;
    }

    public String getTimezone() {
        return timezone;
    }

    public long getUpdateInterval() {
        return updateInterval;
    }

    public File getFile() {
        return file;
    }
}
