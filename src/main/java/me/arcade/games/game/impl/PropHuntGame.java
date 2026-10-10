package me.arcade.games.game.impl;

import me.arcade.games.ArcadePlugin;
import me.arcade.games.arena.Arena;
import me.arcade.games.game.Game;
import me.arcade.games.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;

public class PropHuntGame extends Game {
    private static final List<Material> DEFAULT_PROPS = List.of(
            Material.CRAFTING_TABLE, Material.BOOKSHELF, Material.FURNACE, Material.BRICKS, Material.HAY_BLOCK,
            Material.PUMPKIN, Material.MELON, Material.TNT, Material.JUKEBOX, Material.BARREL, Material.OAK_LOG,
            Material.COBBLESTONE, Material.STONE_BRICKS, Material.OAK_PLANKS, Material.SANDSTONE);
    private static final int HIDE_TICKS = 30 * 20;
    private static final int SEEK_TICKS = 5 * 60 * 20;

    private final Set<UUID> hiders = new HashSet<>();
    private final Set<UUID> seekers = new HashSet<>();
    /** Falling blocks are removed by the server after 600 ticks, so the rider is respawned before that. */
    private static final int RESPAWN_TICKS = 300;

    /**
     * One hider's disguise. For normal blocks, {@code base} is an invisible marker armor stand that is moved
     * every tick and {@code fb} is a FallingBlock riding it (the server syncs falling blocks only about once a
     * second, so a rider on a fast-updating carrier looks far smoother). For block-entity blocks, {@code base}
     * is a BlockDisplay and {@code fb} is null.
     */
    private static final class Prop {
        Entity base;
        FallingBlock fb;
        Material type;
        int age;

        void remove() {
            if (fb != null) fb.remove();
            if (base != null) base.remove();
        }
    }

    private final Map<UUID, Prop> disguises = new HashMap<>();
    private final Random rnd = new Random();
    private boolean seeking;
    private int timer;
    private Team hideTeam;
    private List<Material> props = DEFAULT_PROPS;

    public PropHuntGame(ArcadePlugin plugin, Arena arena) {
        super(plugin, arena);
    }


    /** Reads a list of block names from config.yml; unknown names are skipped with a warning. */
    private List<Material> configured(String path) {
        List<Material> out = new ArrayList<>();
        for (String name : plugin.getConfig().getStringList(path)) {
            Material m = Material.matchMaterial(name.trim());
            if (m != null && m.isBlock()) out.add(m);
            else plugin.getLogger().warning("Prop Hunt: unknown block '" + name + "' in " + path);
        }
        return out;
    }

    @Override
    protected void onStart() {
        List<Material> start = configured("prophunt.start-blocks");
        props = start.isEmpty() ? DEFAULT_PROPS : start;
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        hideTeam = sb.getTeam("ah_hide");
        if (hideTeam == null) hideTeam = sb.registerNewTeam("ah_hide");
        hideTeam.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);

        List<Player> list = allPlayers();
        Collections.shuffle(list);
        int seekerCount = Math.max(1, list.size() / 5);
        List<Location> sp = arena.spawnList();
        Location hiderSpawn = sp.get(0);
        Location seekerSpawn = sp.size() > 1 ? sp.get(1) : sp.get(0);
        seeking = false;
        timer = HIDE_TICKS;

        for (int i = 0; i < list.size(); i++) {
            Player p = list.get(i);
            if (i < seekerCount) {
                seekers.add(p.getUniqueId());
                p.teleport(seekerSpawn);
                p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, HIDE_TICKS + 40, 0, false, false, false));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, HIDE_TICKS, 6, false, false, false));
                giveSeekerKit(p);
                Msg.title(p, "&c&lSEEKER", "&7Released in 30 seconds");
            } else {
                hiders.add(p.getUniqueId());
                p.teleport(hiderSpawn);
                p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false));
                hideTeam.addEntry(p.getName());
                disguise(p, props.get(rnd.nextInt(props.size())));
                p.getInventory().addItem(Msg.item(Material.NETHER_STAR, 1, "&bDisguise Changer",
                        "&7Right-click a solid block", "&7to turn into it."));
                Msg.title(p, "&a&lHIDER", "&7You are a block - hide!");
            }
        }
        broadcast("&e" + seekerCount + " &7seeker(s) were chosen. Hiders have &e30s &7to hide!");
    }

    private void giveSeekerKit(Player p) {
        p.getInventory().clear();
        p.getInventory().addItem(Msg.item(Material.IRON_SWORD, 1, "&cSeeker Blade"));
    }

    /**
     * Blocks drawn by a block-entity renderer (chests, beds, signs, ...). A falling block can't show these on
     * any client, so they use a BlockDisplay (Java players only - Bedrock cannot show display entities).
     */
    private static boolean needsDisplay(Material m) {
        String n = m.name();
        return n.endsWith("CHEST") || n.endsWith("_BED") || n.endsWith("SHULKER_BOX") || n.endsWith("_BANNER")
                || n.contains("SIGN") || n.endsWith("_HEAD") || n.endsWith("_SKULL") || n.equals("BELL")
                || n.equals("CONDUIT") || n.equals("ENCHANTING_TABLE") || n.equals("DECORATED_POT");
    }

    private static Location flat(Location l) {
        Location c = l.clone();
        c.setYaw(0f);
        c.setPitch(0f);
        return c;
    }

    private double yOffset() {
        return plugin.getConfig().getDouble("prophunt.block-y-offset", 0.0);
    }

    /** Spawns a FallingBlock showing {@code m} and mounts it on {@code carrier}. */
    private FallingBlock spawnFalling(Location at, Material m, Entity carrier) {
        FallingBlock fb = at.getWorld().spawnFallingBlock(at, m.createBlockData());
        fb.setGravity(false);
        fb.setDropItem(false);
        fb.setHurtEntities(false);
        fb.setPersistent(false);
        fb.setInvulnerable(true);
        fb.addScoreboardTag("ag_fall"); // GameListener cancels any attempt of it turning into a real block
        carrier.addPassenger(fb);
        return fb;
    }

    /**
     * Creates the disguise. Java AND Bedrock (through Geyser) can see a FallingBlock; BlockDisplay is invisible
     * to Bedrock, so it is only used for blocks a falling block cannot draw.
     */
    private Prop spawnProp(Location l, Material m) {
        World w = l.getWorld();
        Prop pr = new Prop();
        pr.type = m;
        if (needsDisplay(m)) {
            pr.base = w.spawn(l, BlockDisplay.class, bd -> {
                bd.setBlock(m.createBlockData());
                bd.setTransformation(new Transformation(new Vector3f(-0.5f, 0f, -0.5f), new AxisAngle4f(),
                        new Vector3f(1f, 1f, 1f), new AxisAngle4f()));
                bd.setTeleportDuration(1);
                bd.setPersistent(false);
            });
            return pr;
        }
        Location at = l.clone().add(0, yOffset(), 0);
        pr.base = w.spawn(at, ArmorStand.class, as -> {
            as.setInvisible(true);
            as.setMarker(true);
            as.setGravity(false);
            as.setSilent(true);
            as.setInvulnerable(true);
            as.setBasePlate(false);
            as.setPersistent(false);
            as.addScoreboardTag("ag_fall");
        });
        pr.fb = spawnFalling(at, m, pr.base);
        return pr;
    }

    private void move(Prop pr, Location l) {
        if (pr.fb != null) {
            pr.base.teleport(l.clone().add(0, yOffset(), 0), PlayerTeleportEvent.TeleportCause.PLUGIN,
                    TeleportFlag.EntityState.RETAIN_PASSENGERS);
        } else {
            pr.base.teleport(l);
        }
    }

    private void disguise(Player p, Material m) {
        disguises.put(p.getUniqueId(), spawnProp(flat(p.getLocation()), m));
    }

    private void undisguise(Player p) {
        Prop pr = disguises.remove(p.getUniqueId());
        if (pr != null) pr.remove();
        if (hideTeam != null) hideTeam.removeEntry(p.getName());
    }

    @Override
    protected void tick() {
        for (Map.Entry<UUID, Prop> en : disguises.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null) continue;
            Prop pr = en.getValue();
            Location l = flat(p.getLocation());
            if (pr.base.isDead() || !pr.base.isValid()) {
                // carrier gone: rebuild everything (new one first, so there is no flicker)
                Prop fresh = spawnProp(l, pr.type);
                pr.remove();
                en.setValue(fresh);
                continue;
            }
            pr.age++;
            if (pr.fb != null && (pr.age >= RESPAWN_TICKS || pr.fb.isDead() || !pr.fb.isValid())) {
                FallingBlock old = pr.fb;
                pr.fb = spawnFalling(pr.base.getLocation(), pr.type, pr.base);
                old.remove();
                pr.age = 0;
            }
            move(pr, l);
        }
        timer--;
        if (!seeking) {
            if (timer <= 0) {
                seeking = true;
                timer = SEEK_TICKS;
                for (UUID id : seekers) {
                    Player p = Bukkit.getPlayer(id);
                    if (p == null) continue;
                    p.removePotionEffect(PotionEffectType.BLINDNESS);
                    p.removePotionEffect(PotionEffectType.SLOWNESS);
                }
                broadcast("&c&lThe seekers have been released!");
                sound(Sound.ENTITY_ENDER_DRAGON_GROWL);
            } else if (ticks % 20 == 0) {
                for (Player p : allPlayers()) Msg.bar(p, "&eSeekers released in &b" + (timer / 20 + 1) + "s");
            }
        } else {
            if (timer <= 0) {
                List<String> names = new ArrayList<>();
                for (UUID id : hiders) names.add(nameOf(id));
                end("&a&lHiders win! &7They survived: &f" + String.join(", ", names));
                return;
            }
            if (ticks % 20 == 0) {
                int secs = timer / 20;
                for (Player p : allPlayers()) {
                    Msg.bar(p, (hiders.contains(p.getUniqueId()) ? "&aHider" : "&cSeeker")
                            + " &8| &7Hiders left: &e" + hiders.size()
                            + " &8| &7Time: &e" + (secs / 60) + ":" + String.format("%02d", secs % 60));
                }
            }
        }
    }

    @Override
    public void onDamage(Player victim, EntityDamageEvent e) {
        e.setCancelled(true);
        if (!seeking) return;
        Player d = attacker(e);
        if (d != null && seekers.contains(d.getUniqueId()) && hiders.contains(victim.getUniqueId())) {
            caught(victim, d);
        }
    }

    private void caught(Player victim, Player seeker) {
        UUID id = victim.getUniqueId();
        hiders.remove(id);
        seekers.add(id);
        undisguise(victim);
        victim.removePotionEffect(PotionEffectType.INVISIBILITY);
        giveSeekerKit(victim);
        broadcast("&c" + victim.getName() + " &7was found by &e" + seeker.getName() + "&7! &e" + hiders.size() + " &7hider(s) left.");
        victim.playSound(victim.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 1f);
        Msg.title(victim, "&cFound!", "&7You are now a seeker");
        checkEnd();
    }

    @Override
    public void onInteract(Player p, PlayerInteractEvent e) {
        if (!hiders.contains(p.getUniqueId()) || e.getItem() == null || e.getItem().getType() != Material.NETHER_STAR) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        e.setCancelled(true);
        Material m = e.getClickedBlock().getType();
        List<Material> allowed = configured("prophunt.allowed-blocks");
        boolean ok = allowed.isEmpty() ? (m.isSolid() && m.isOccluding()) : allowed.contains(m);
        if (!ok) {
            Msg.bar(p, "&cYou can't disguise as that block.");
            return;
        }
        Prop old = disguises.get(p.getUniqueId());
        if (old != null) {
            disguises.put(p.getUniqueId(), spawnProp(flat(p.getLocation()), m));
            old.remove();
        }
        Msg.bar(p, "&aYou are now a &e" + m.name().toLowerCase().replace('_', ' '));
    }

    @Override
    protected void onLeave(Player p, boolean wasAlive) {
        hiders.remove(p.getUniqueId());
        seekers.remove(p.getUniqueId());
        undisguise(p);
    }

    @Override
    protected void checkEnd() {
        if (hiders.isEmpty()) end("&c&lSeekers win! &7Every hider was found.");
        else if (seekers.isEmpty()) end("&a&lHiders win! &7All the seekers left.");
    }

    @Override
    protected void onEnd() {
        for (Prop pr : disguises.values()) pr.remove();
        disguises.clear();
        if (hideTeam != null) {
            for (String entry : new ArrayList<>(hideTeam.getEntries())) hideTeam.removeEntry(entry);
        }
        hiders.clear();
        seekers.clear();
    }
}
