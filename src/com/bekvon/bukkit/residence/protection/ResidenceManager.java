package com.bekvon.bukkit.residence.protection;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.api.ResidenceInterface;
import com.bekvon.bukkit.residence.containers.Flags;
import com.bekvon.bukkit.residence.containers.MinimizeFlags;
import com.bekvon.bukkit.residence.containers.MinimizeMessages;
import com.bekvon.bukkit.residence.containers.ResidencePlayer;
import com.bekvon.bukkit.residence.containers.Visualizer;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.economy.rent.RentableLand;
import com.bekvon.bukkit.residence.economy.rent.RentedLand;
import com.bekvon.bukkit.residence.event.ResidenceCreationEvent;
import com.bekvon.bukkit.residence.event.ResidenceDeleteEvent;
import com.bekvon.bukkit.residence.event.ResidenceDeleteEvent.DeleteCause;
import com.bekvon.bukkit.residence.event.ResidenceRenameEvent;
import com.bekvon.bukkit.residence.listeners.ResidenceLWCListener;
import com.bekvon.bukkit.residence.permissions.PermissionGroup;
import com.bekvon.bukkit.residence.permissions.PermissionManager.ResPerm;
import com.bekvon.bukkit.residence.protection.FlagPermissions.FlagCombo;
import com.bekvon.bukkit.residence.utils.GetTime;

import net.Zrips.CMILib.Colors.CMIChatColor;
import net.Zrips.CMILib.Container.CMINumber;
import net.Zrips.CMILib.Container.PageInfo;
import net.Zrips.CMILib.Logs.CMIDebug;
import net.Zrips.CMILib.RawMessages.RawMessage;
import net.Zrips.CMILib.Version.Version;
import net.Zrips.CMILib.Version.Schedulers.CMIScheduler;

public class ResidenceManager implements ResidenceInterface {
    protected ConcurrentHashMap<String, ClaimedResidence> residences;
    protected Map<String, Map<ChunkRef, List<ClaimedResidence>>> chunkResidences;
    protected List<ClaimedResidence> shops = new ArrayList<ClaimedResidence>();
    private Residence plugin;

    public ResidenceManager(Residence plugin) {
        residences = new ConcurrentHashMap<String, ClaimedResidence>();
        chunkResidences = new HashMap<String, Map<ChunkRef, List<ClaimedResidence>>>();
        shops = new ArrayList<ClaimedResidence>();
        this.plugin = plugin;
    }

    public boolean isOwnerOfLocation(Player player, Location loc) {
        ClaimedResidence res = getByLoc(loc);
        if (res != null && res.isOwner(player))
            return true;
        return false;
    }

    public ClaimedResidence getByLoc(Player player) {
        return getByLoc(player.getLocation());
    }

    @Override
    public ClaimedResidence getByLoc(Location loc) {

        if (loc == null)
            return null;

        World world = loc.getWorld();

        if (world == null)
            return null;

        String worldName = world.getName();

        if (worldName == null)
            return null;

        Map<ChunkRef, List<ClaimedResidence>> ChunkMap = chunkResidences.get(worldName);

        if (ChunkMap == null)
            return null;

        List<ClaimedResidence> residences = ChunkMap.get(new ChunkRef(loc));

        if (residences == null)
            return null;

        for (ClaimedResidence residence : residences) {
            if (residence == null)
                continue;
            if (!residence.containsLoc(loc))
                continue;

            ClaimedResidence subres = residence.getSubzoneByLoc(loc);
            return subres == null ? residence : subres;

        }
        return null;
    }

    public List<ClaimedResidence> getByChunk(Chunk chunk) {
        List<ClaimedResidence> list = new ArrayList<ClaimedResidence>();
        if (chunk == null)
            return list;
        World world = chunk.getWorld();
        if (world == null)
            return list;
        String worldName = world.getName();
        if (worldName == null)
            return list;
        if (!chunkResidences.containsKey(worldName))
            return list;
        ChunkRef chunkRef = new ChunkRef(chunk.getX(), chunk.getZ());
        Map<ChunkRef, List<ClaimedResidence>> ChunkMap = chunkResidences.get(worldName);
        List<ClaimedResidence> ls = ChunkMap.get(chunkRef);
        return ls == null ? list : new ArrayList<ClaimedResidence>(ls);
    }

    @Override
    public ClaimedResidence getByName(String name) {
        if (name == null) {
            return null;
        }
        String[] split = name.split("\\.");
        if (split.length == 1) {
            return residences.get(name.toLowerCase());
        }
        ClaimedResidence res = residences.get(split[0].toLowerCase());
        for (int i = 1; i < split.length; i++) {
            if (res != null) {
                res = res.getSubzone(split[i].toLowerCase());
            } else {
                return null;
            }
        }
        return res;
    }

    @Override
    public String getSubzoneNameByRes(ClaimedResidence res) {
        Set<Entry<String, ClaimedResidence>> set = residences.entrySet();
        for (Entry<String, ClaimedResidence> check : set) {
            if (check.getValue() == res) {
                return check.getKey();
            }
            String n = check.getValue().getSubzoneNameByRes(res);
            if (n != null) {
                return n;
            }
        }
        return null;
    }

    @Override
    public void addShop(String resName) {
        ClaimedResidence res = getByName(resName);
        if (res != null)
            shops.add(res);
    }

    @Override
    public void addShop(ClaimedResidence res) {
        shops.add(res);
    }

    @Override
    public void removeShop(ClaimedResidence res) {
        shops.remove(res);
    }

    @Override
    public void removeShop(String resName) {
        for (ClaimedResidence one : shops) {
            if (one.getName().equalsIgnoreCase(resName)) {
                shops.remove(one);
                break;
            }
        }
    }

    @Override
    public List<ClaimedResidence> getShops() {
        return shops;
    }

    @Override
    public boolean addResidence(String name, Location loc1, Location loc2) {
        return this.addResidence(name, plugin.getServerLandName(), loc1, loc2);
    }

    @Override
    public boolean addResidence(String name, String owner, Location loc1, Location loc2) {
        return this.addResidence(null, owner, name, loc1, loc2, true);
    }

    @Override
    public boolean addResidence(Player player, String name, Location loc1, Location loc2, boolean resadmin) {
        return this.addResidence(player, player.getName(), name, loc1, loc2, resadmin);
    }

    public boolean addResidence(Player player, String owner, String name, Location loc1, Location loc2, boolean resadmin) {
        return addResidence(player, owner, null, name, loc1, loc2, resadmin);
    }

    @Override
    public boolean addResidence(Player player, String resName, boolean resadmin) {
        return addResidence(player, player.getName(), player.getUniqueId(), resName, plugin.getSelectionManager().getPlayerLoc1(player), plugin
            .getSelectionManager().getPlayerLoc2(player), resadmin);
    }

    public boolean addResidence(Player player, String owner, UUID ownerUUId, String resName, Location loc1, Location loc2, boolean resadmin) {
        if (!plugin.validName(resName)) {
            plugin.msg(player, lm.Invalid_NameCharacters);
            return false;
        }
        if (loc1 == null || loc2 == null || !loc1.getWorld().getName().equals(loc2.getWorld().getName())) {
            plugin.msg(player, lm.Select_Points);
            return false;
        }

        if (plugin.isDisabledWorld(loc1.getWorld()) && plugin.getConfigManager().isDisableResidenceCreation()) {
            plugin.msg(player, lm.General_CantCreate);
            return false;
        }

        ResidencePlayer rPlayer = plugin.getPlayerManager().getResidencePlayer(player);

        PermissionGroup group = rPlayer.getGroup();
//	PermissionGroup group = plugin.getPermissionManager().getGroup(owner, loc1.getWorld().getName());
        if (!resadmin && !group.canCreateResidences() && !ResPerm.create.hasPermission(player, lm.General_NoPermission)) {
            return false;
        }

        if (!resadmin && !ResPerm.create.hasPermission(player, true)) {
            return false;
        }

        if (rPlayer.getResAmount() >= rPlayer.getMaxRes() && !resadmin) {
            plugin.msg(player, lm.Residence_TooMany);
            return false;
        }

        CuboidArea newArea = new CuboidArea(loc1, loc2);
        ClaimedResidence newRes = new ClaimedResidence(owner, ownerUUId, loc1.getWorld().getName());
        newRes.getPermissions().applyDefaultFlags();
        newRes.setEnterMessage(group.getDefaultEnterMessage());
        newRes.setLeaveMessage(group.getDefaultLeaveMessage());
        newRes.setName(resName);
        newRes.setCreateTime();

        if (residences.containsKey(resName.toLowerCase())) {
            plugin.msg(player, lm.Residence_AlreadyExists, residences.get(resName.toLowerCase()).getResidenceName());
            return false;
        } 

        newRes.BlockSellPrice = group.getSellPerBlock();

        if (!newRes.addArea(player, newArea, "main", resadmin, false))
            return false;

        ResidenceCreationEvent resevent = new ResidenceCreationEvent(player, resName, newRes, newArea);
        plugin.getServ().getPluginManager().callEvent(resevent);
        if (resevent.isCancelled())
            return false;

        if (Residence.getInstance().getConfigManager().isChargeOnCreation() && !newRes.isSubzone() && plugin.getConfigManager().enableEconomy() && !resadmin) {
            double chargeamount = newArea.getCost(group);

            if (chargeamount > 0 && !plugin.getTransactionManager().chargeEconomyMoney(player, chargeamount)) {
                // Need to remove area if we can't create residence
                newRes.removeArea("main");
                return false;
            }
        }

        residences.put(resName.toLowerCase(), newRes);

        calculateChunks(resName);
        plugin.getLeaseManager().removeExpireTime(newRes);
        plugin.getPlayerManager().addResidence(newRes.getOwner(), newRes);

        if (player != null) {
            Visualizer v = new Visualizer(player);
            v.setAreas(newArea);
            plugin.getSelectionManager().showBounds(player, v);
            plugin.getAutoSelectionManager().getList().remove(player.getUniqueId());
            plugin.msg(player, lm.Area_Create, "main");
            plugin.msg(player, lm.Residence_Create, resName);
        }
        if (plugin.getConfigManager().useLeases()) {
            plugin.getLeaseManager().setExpireTime(player, newRes, group.getLeaseGiveTime());
        }
        return true;

    }

    public void listResidences(CommandSender sender) {
        this.listResidences(sender, sender.getName(), 1);
    }

    public void listResidences(CommandSender sender, boolean resadmin) {
        this.listResidences(sender, sender.getName(), 1, false, false, resadmin);
    }

    public void listResidences(CommandSender sender, String targetplayer, boolean showhidden) {
        this.listResidences(sender, targetplayer, 1, showhidden, false, showhidden);
    }

    public void listResidences(CommandSender sender, String targetplayer, int page) {
        this.listResidences(sender, targetplayer, page, false, false, false);
    }

    public void listResidences(CommandSender sender, int page, boolean showhidden) {
        this.listResidences(sender, sender.getName(), page, showhidden, false, showhidden);
    }

    public void listResidences(CommandSender sender, int page, boolean showhidden, boolean onlyHidden) {
        this.listResidences(sender, sender.getName(), page, showhidden, onlyHidden, showhidden);
    }

    public void listResidences(CommandSender sender, String string, int page, boolean showhidden) {
        this.listResidences(sender, string, page, showhidden, false, showhidden);
    }

    public void listResidences(CommandSender sender, String targetplayer, int page, boolean showhidden, boolean onlyHidden, boolean resadmin) {
        this.listResidences(sender, targetplayer, page, showhidden, onlyHidden, resadmin, null);
    }

    public void listResidences(CommandSender sender, String targetplayer, int page, boolean showhidden, boolean onlyHidden, boolean resadmin, World world) {
        if (targetplayer == null)
            targetplayer = sender.getName();
        if (showhidden && !plugin.isResAdminOn(sender) && !sender.getName().equalsIgnoreCase(targetplayer)) {
            showhidden = false;
        } else if (sender.getName().equalsIgnoreCase(targetplayer))
            showhidden = true;
        boolean hidden = showhidden;
        TreeMap<String, ClaimedResidence> ownedResidences = plugin.getPlayerManager().getResidencesMap(targetplayer, hidden, onlyHidden, world);
        ownedResidences.putAll(plugin.getRentManager().getRentsMap(targetplayer, onlyHidden, world));
        ownedResidences.putAll(plugin.getPlayerManager().getTrustedResidencesMap(targetplayer, hidden, onlyHidden, world));

        plugin.getInfoPageManager().printListInfo(sender, targetplayer, ownedResidences, page, resadmin, world);
    }

    public void listAllResidences(CommandSender sender, int page) {
        this.listAllResidences(sender, page, false);
    }

    public void listAllResidences(CommandSender sender, int page, boolean showhidden, World world) {
        TreeMap<String, ClaimedResidence> list = getFromAllResidencesMap(showhidden, false, world);
        plugin.getInfoPageManager().printListInfo(sender, null, list, page, showhidden, world);
    }

    public void listAllResidences(CommandSender sender, int page, boolean showhidden) {
        this.listAllResidences(sender, page, showhidden, false);
    }

    public void listAllResidences(CommandSender sender, int page, boolean showhidden, boolean onlyHidden) {
        TreeMap<String, ClaimedResidence> list = getFromAllResidencesMap(showhidden, onlyHidden, null);
        plugin.getInfoPageManager().printListInfo(sender, null, list, page, showhidden, null);
    }

    public String[] getResidenceList() {
        return this.getResidenceList(true, true).toArray(new String[0]);
    }

    public Map<String, ClaimedResidence> getResidenceMapList(String targetplayer, boolean showhidden) {
        Map<String, ClaimedResidence> temp = new HashMap<String, ClaimedResidence>();
        for (Entry<String, ClaimedResidence> res : residences.entrySet()) {
            if (res.getValue().isOwner(targetplayer)) {
                boolean hidden = res.getValue().getPermissions().has("hidden", false);
                if ((showhidden) || (!showhidden && !hidden)) {
                    temp.put(res.getValue().getName().toLowerCase(), res.getValue());
                }
            }
        }
        return temp;
    }

    public ArrayList<String> getResidenceList(boolean showhidden, boolean showsubzones) {
        return this.getResidenceList(null, showhidden, showsubzones, false);
    }

    public ArrayList<String> getResidenceList(String targetplayer, boolean showhidden, boolean showsubzones) {
        return this.getResidenceList(targetplayer, showhidden, showsubzones, false, false);
    }

    public ArrayList<String> getResidenceList(String targetplayer, boolean showhidden, boolean showsubzones, boolean onlyHidden) {
        return this.getResidenceList(targetplayer, showhidden, showsubzones, false, onlyHidden);
    }

    public ArrayList<String> getResidenceList(String targetplayer, boolean showhidden, boolean showsubzones, boolean formattedOutput, boolean onlyHidden) {
        ArrayList<String> list = new ArrayList<>();
        for (Entry<String, ClaimedResidence> res : residences.entrySet()) {
            this.getResidenceList(targetplayer, showhidden, showsubzones, "", res.getKey(), res.getValue(), list, formattedOutput, onlyHidden);
        }
        return list;
    }

    public ArrayList<ClaimedResidence> getFromAllResidences(boolean showhidden, boolean onlyHidden, World world) {
        ArrayList<ClaimedResidence> list = new ArrayList<>();
        for (Entry<String, ClaimedResidence> res : residences.entrySet()) {
            boolean hidden = res.getValue().getPermissions().has("hidden", false);
            if (onlyHidden && !hidden)
                continue;
            if (world != null && !world.getName().equalsIgnoreCase(res.getValue().getWorld()))
                continue;
            if ((showhidden) || (!showhidden && !hidden)) {
                list.add(res.getValue());
            }
        }
        return list;
    }

    public TreeMap<String, ClaimedResidence> getFromAllResidencesMap(boolean showhidden, boolean onlyHidden, World world) {
        TreeMap<String, ClaimedResidence> list = new TreeMap<String, ClaimedResidence>();
        for (Entry<String, ClaimedResidence> res : residences.entrySet()) {
            boolean hidden = res.getValue().getPermissions().has("hidden", false);
            if (onlyHidden && !hidden)
                continue;
            if (world != null && !world.getName().equalsIgnoreCase(res.getValue().getWorld()))
                continue;
            if ((showhidden) || (!showhidden && !hidden)) {
                list.put(res.getKey(), res.getValue());
            }
        }
        return list;
    }

    private void getResidenceList(String targetplayer, boolean showhidden, boolean showsubzones, String parentzone, String resname, ClaimedResidence res,
        ArrayList<String> list, boolean formattedOutput, boolean onlyHidden) {
        boolean hidden = res.getPermissions().has("hidden", false);

        if (onlyHidden && !hidden)
            return;

        if ((showhidden) || (!showhidden && !hidden)) {
            if (targetplayer == null || res.getPermissions().getOwner().equals(targetplayer)) {
                if (formattedOutput) {
                    list.add(plugin.msg(lm.Residence_List, parentzone, resname, res.getWorld()) +
                        (hidden ? plugin.msg(lm.Residence_Hidden) : ""));
                } else {
                    list.add(parentzone + resname);
                }
            }
            if (showsubzones) {
                for (Entry<String, ClaimedResidence> sz : res.subzones.entrySet()) {
                    this.getResidenceList(targetplayer, showhidden, showsubzones, parentzone + resname + ".", sz.getKey(), sz.getValue(), list, formattedOutput,
                        onlyHidden);
                }
            }
        }
    }

    public String checkAreaCollision(CuboidArea newarea, ClaimedResidence parentResidence) {
        Set<Entry<String, ClaimedResidence>> set = residences.entrySet();
        for (Entry<String, ClaimedResidence> entry : set) {
            ClaimedResidence check = entry.getValue();
            if (check != parentResidence && check.checkCollision(newarea)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public String checkAreaCollision(CuboidArea newarea, ClaimedResidence parentResidence, UUID ignoredOwner) {
        Set<Entry<String, ClaimedResidence>> set = residences.entrySet();
        for (Entry<String, ClaimedResidence> entry : set) {
            ClaimedResidence check = entry.getValue();
            if (check != parentResidence && check.checkCollision(newarea)) {
                if (ignoredOwner == null || !entry.getValue().isOwner(ignoredOwner))
                    return entry.getKey();
            }
        }
        return null;
    }

    public Set<ClaimedResidence> getResidences(String worldName, List<ChunkRef> chunks) {

        Map<ChunkRef, List<ClaimedResidence>> refs = chunkResidences.get(worldName);

        Set<ClaimedResidence> resSet = new HashSet<ClaimedResidence>();

        if (refs == null)
            return resSet;

        for (ChunkRef one : chunks) {
            List<ClaimedResidence> res = refs.get(one);
            if (res != null)
                resSet.addAll(res);
        }

        return resSet;
    }

    public ClaimedResidence collidesWithResidence(CuboidArea newarea) {
        Set<ClaimedResidence> res = getResidences(newarea.getWorldName(), newarea.getChunks());
        for (ClaimedResidence check : res) {
            if (check.checkCollision(newarea)) {
                return check;
            }
        }
        return null;
    }

    public void removeResidence(ClaimedResidence res) {
        this.removeResidence(null, res.getName(), true);
    }

    public void removeResidence(String name) {
        this.removeResidence(null, name, true);
    }

    public void removeResidence(CommandSender sender, String name, boolean resadmin) {
        if (sender instanceof Player)
            removeResidence((Player) sender, name, resadmin);
        else
            removeResidence(null, name, true);
    }

    @Deprecated
    public void removeResidence(Player player, String name, boolean resadmin) {
        ClaimedResidence res = this.getByName(name);
        if (res == null) {
            plugin.msg(player, lm.Invalid_Residence);
            return;
        }
        removeResidence(player, res, resadmin);
    }

    @Deprecated
    public void removeResidence(Player player, ClaimedResidence res, boolean resadmin) {
        removeResidence(plugin.getPlayerManager().getResidencePlayer(player), res, resadmin);
    }

    public void removeResidence(ResidencePlayer rPlayer, ClaimedResidence res, boolean resadmin) {
        removeResidence(rPlayer, res, resadmin, false);
    }

    @SuppressWarnings("deprecation")
    public void removeResidence(ResidencePlayer rPlayer, ClaimedResidence res, boolean resadmin, boolean regenerate) {

        Player player = null;
        if (rPlayer != null)
            player = rPlayer.getPlayer();

        if (res == null) {
            plugin.msg(player, lm.Invalid_Residence);
            return;
        }

        String name = res.getName();

        if (plugin.getConfigManager().isRentPreventRemoval() && !resadmin) {
            ClaimedResidence rented = res.getRentedSubzone();
            if (rented != null) {
                plugin.msg(player, lm.Residence_CantRemove, res.getName(), rented.getName(), rented.getRentedLand().player);
                return;
            }
            if (player != null && res.isRented() && !player.getName().equalsIgnoreCase(res.getRentedLand().player)) {
                plugin.msg(player, lm.Residence_CantRemove, res.getName(), res.getName(), res.getRentedLand().player);
                return;
            }
        }

        if (player != null && !resadmin) {
            if (!res.getPermissions().hasResidencePermission(player, true) && !resadmin && res.getParent() != null && !res.getParent().isOwner(player)) {
                plugin.msg(player, lm.General_NoPermission);
                return;
            }
        }

        if (rPlayer != null)
            rPlayer.forceUpdateGroup();

        ResidenceDeleteEvent resevent = new ResidenceDeleteEvent(player, res, rPlayer == null ? DeleteCause.OTHER : DeleteCause.PLAYER_DELETE);
        plugin.getServ().getPluginManager().callEvent(resevent);
        if (resevent.isCancelled())
            return;

        ClaimedResidence parent = res.getParent();
        if (parent == null) {
            removeChunkList(name);

            residences.remove(name.toLowerCase());

            if (Version.isCurrentEqualOrHigher(Version.v1_13_R1) && plugin.getConfigManager().isUseClean() && plugin.getConfigManager().getCleanWorlds().contains(res.getWorld())) {

                CuboidArea[] arr = res.getAreaArray();
                CMIScheduler.runTaskAsynchronously(() -> {
                    ChunkSnapshot chunkSnapshot = null;
                    int chunkX = 0;
                    int chunkZ = 0;
                    Set<Location> locations = new HashSet<Location>();
                    for (CuboidArea area : arr) {
                        Location low = area.getLowLocation().clone();
                        Location high = area.getHighLocation().clone();

                        if (high.getBlockY() > plugin.getConfigManager().getCleanLevel()) {

                            if (low.getBlockY() < plugin.getConfigManager().getCleanLevel())
                                low.setY(plugin.getConfigManager().getCleanLevel());
                            World world = low.getWorld();
                            for (int x = low.getBlockX(); x <= high.getBlockX(); x++) {
                                for (int z = low.getBlockZ(); z <= high.getBlockZ(); z++) {
                                    int hy = world.getHighestBlockYAt(x, z);
                                    if (high.getBlockY() < hy)
                                        hy = high.getBlockY();

                                    int cx = Math.abs(x % 16);
                                    int cz = Math.abs(z % 16);
                                    if (chunkSnapshot == null || x >> 4 != chunkX || z >> 4 != chunkZ) {
                                        if (!world.getBlockAt(x, 0, z).getChunk().isLoaded()) {
                                            world.getBlockAt(x, 0, z).getChunk().load();
                                            chunkSnapshot = world.getBlockAt(x, 0, z).getChunk().getChunkSnapshot(false, false, false);
                                            world.getBlockAt(x, 0, z).getChunk().unload();
                                        } else {
                                            chunkSnapshot = world.getBlockAt(x, 0, z).getChunk().getChunkSnapshot();
                                        }
                                        chunkX = x >> 4;
                                        chunkZ = z >> 4;
                                    }

                                    if (Version.isCurrentEqualOrHigher(Version.v1_13_R1)) {
                                        for (int y = low.getBlockY(); y <= hy; y++) {
                                            BlockData type = chunkSnapshot.getBlockData(cx, y, cz);
                                            if (!plugin.getConfigManager().getCleanBlocks().contains(type.getMaterial()))
                                                continue;
                                            locations.add(new Location(world, x, y, z));
                                        }
                                    }
                                }
                            }
                        }
                    }
                    CMIScheduler.runTask(() -> {
                        for (Location one : locations) {
                            CMIScheduler.runAtLocation(one, () -> one.getBlock().setType(Material.AIR));
                        }
                    });

                });
            }
            if (plugin.getConfigManager().isRemoveLwcOnDelete() && plugin.isLwcPresent())
                ResidenceLWCListener.removeLwcFromResidence(player, res);
            if (regenerate) {
                for (CuboidArea one : res.getAreaArray()) {
                    plugin.getSelectionManager().regenerate(one);
                }
            }
            plugin.msg(player, lm.Residence_Remove, name);
        } else {
            String[] split = name.split("\\.");
            if (player != null) {
                parent.removeSubzone(player, split[split.length - 1], true);
            } else {
                parent.removeSubzone(split[split.length - 1]);
            }
        }

        plugin.getLeaseManager().removeExpireTime(res);

        for (ClaimedResidence oneSub : res.getSubzones()) {
            plugin.getPlayerManager().removeResFromPlayer(res.getOwnerUUID(), oneSub);
            plugin.getRentManager().removeRentable(name + "." + oneSub.getResidenceName());
            plugin.getTransactionManager().removeFromSale(name + "." + oneSub.getResidenceName());
        }

        plugin.getPlayerManager().removeResFromPlayer(res.getOwnerUUID(), res);
        plugin.getRentManager().removeRentable(name);
        plugin.getTransactionManager().removeFromSale(name);

        if (!res.isServerLand()) {
            if (parent == null && plugin.getConfigManager().enableEconomy() && plugin.getConfigManager().useResMoneyBack()) {
                double chargeamount = res.getWorth();
                if (!res.isOwner(player)) {
                    plugin.getTransactionManager().giveEconomyMoney(res.getOwner(), chargeamount);
                } else {
                    if (player != null)
                        plugin.getTransactionManager().giveEconomyMoney(player, chargeamount);
                    else if (rPlayer != null)
                        plugin.getTransactionManager().giveEconomyMoney(rPlayer.getPlayerName(), chargeamount);
                }
            }

            if (res.getBank().getStoredMoneyD() > 0 && plugin.getConfigManager().isResBankBack()) {
                plugin.getTransactionManager().giveEconomyMoney(res.getOwner(), res.getBank().getStoredMoneyD());
            }
        }
    }

    public void removeAllByOwner(String owner) {
        ResidencePlayer rPlayer = plugin.getPlayerManager().getResidencePlayer(owner);
        for (ClaimedResidence oneRes : rPlayer.getResList()) {
            removeResidence(rPlayer, oneRes, true);
        }
    }

    public int getOwnedZoneCount(String player) {
        ResidencePlayer rPlayer = plugin.getPlayerManager().getResidencePlayer(player);
        return rPlayer.getResAmount();
    }

    public boolean hasMaxZones(String player, int target) {
        return getOwnedZoneCount(player) < target;
    }

    public void printAreaInfo(String areaname, CommandSender sender) {
        printAreaInfo(areaname, sender, false);
    }

    public void printAreaInfo(String areaname, CommandSender sender, boolean resadmin) {
        ClaimedResidence res = this.getByName(areaname);
        if (res == null) {
            plugin.msg(sender, lm.Invalid_Residence);
            return;
        }

        areaname = res.getName();

        plugin.msg(sender, lm.General_Separator);

        ResidencePermissions perms = res.getPermissions();

        String resNameOwner = "&e" + plugin.msg(lm.Residence_Line, areaname);
        resNameOwner += plugin.msg(lm.General_Owner, perms.getOwner());
        if (plugin.getConfigManager().enableEconomy()) {
            if (res.isOwner(sender) || !(sender instanceof Player) || resadmin)
                resNameOwner += plugin.msg(lm.Bank_Name, res.getBank().getStoredMoneyFormated());
        }
        resNameOwner = CMIChatColor.translate(resNameOwner);

        String worldInfo = plugin.msg(lm.General_World, perms.getWorldName());

        if (res.getAreaArray().length > 0 && (res.getPermissions().has(Flags.hidden, FlagCombo.FalseOrNone) && res.getPermissions().has(Flags.coords, FlagCombo.TrueOrNone) || resadmin)) {
            CuboidArea area = res.getAreaArray()[0];
            String cord1 = plugin.msg(lm.General_CoordsTop, area.getHighVector().getBlockX(), area.getHighVector().getBlockY(), area.getHighVector().getBlockZ());
            String cord2 = plugin.msg(lm.General_CoordsBottom, area.getLowVector().getBlockX(), area.getLowVector().getBlockY(), area.getLowVector().getBlockZ());
            worldInfo += CMIChatColor.translate(plugin.msg(lm.General_CoordsLiner, cord1, cord2));
        }

        worldInfo += "\n" + plugin.msg(lm.General_CreatedOn, GetTime.getTime(res.createTime));

        String ResFlagList = perms.listFlags(5);
        if (!(sender instanceof Player))
            ResFlagList = perms.listFlags();
        String ResFlagMsg = plugin.msg(lm.General_ResidenceFlags, ResFlagList);

        if (perms.getFlags().size() > 2 && sender instanceof Player) {
            ResFlagMsg = plugin.msg(lm.General_ResidenceFlags, perms.listFlags(5, 3)) + "...";
        }

        if (sender instanceof Player) {
            RawMessage rm = new RawMessage();
            rm.addText(resNameOwner).addHover(worldInfo);
            rm.show(sender);

            rm = new RawMessage();

            rm.addText(ResFlagMsg).addHover(ResFlagList);
            rm.show(sender);
        } else {
            plugin.msg(sender, resNameOwner);
            plugin.msg(sender, worldInfo);
            plugin.msg(sender, ResFlagMsg);
        }

        if (!plugin.getConfigManager().isShortInfoUse() || !(sender instanceof Player))
            sender.sendMessage(plugin.msg(lm.General_PlayersFlags, perms.listPlayersFlags()));
        else if (plugin.getConfigManager().isShortInfoUse() || sender instanceof Player) {

            RawMessage rm = perms.listPlayersFlagsRaw(sender.getName(), plugin.msg(lm.General_PlayersFlags, ""));
            rm.addCommand("res info " + res.getName() + " -players");
            rm.show(sender);
        }

        String groupFlags = perms.listGroupFlags();
        if (groupFlags.length() > 0)
            plugin.msg(sender, lm.General_GroupFlags, groupFlags);

        RawMessage rm = new RawMessage();
        rm.addText(plugin.msg(lm.General_TotalResSize, res.getTotalSize(), res.getXZSize()));

        try {
            rm.addHover(Arrays.asList(
                plugin.msg(lm.General_ResSize_eastWest, res.getMainArea().getXSize()),
                plugin.msg(lm.General_ResSize_northSouth, res.getMainArea().getZSize()),
                plugin.msg(lm.General_ResSize_upDown, res.getMainArea().getYSize())));
        } catch (Throwable e) {
            e.printStackTrace();
        }

        rm.show(sender);

        if (plugin.getEconomyManager() != null) {
            plugin.msg(sender, lm.General_TotalWorth, plugin.getEconomyManager().format(res.getWorthByOwner()), plugin.getEconomyManager().format(res.getWorth()));
        }

        if (res.getSubzonesAmount(false) > 0)
            plugin.msg(sender, lm.General_TotalSubzones, res.getSubzonesAmount(false), res.getSubzonesAmount(true));

        if (plugin.getConfigManager().useLeases() && plugin.getLeaseManager().isLeased(res)) {
            String time = plugin.getLeaseManager().getExpireTime(res);
            if (time != null)
                plugin.msg(sender, lm.Economy_LeaseExpire, time);
        }

        if (plugin.getConfigManager().enabledRentSystem() && plugin.getRentManager().isForRent(areaname) && !plugin.getRentManager().isRented(areaname)) {
            String forRentMsg = plugin.msg(lm.Rent_isForRent);

            RentableLand rentable = plugin.getRentManager().getRentableLand(areaname);
            StringBuilder rentableString = new StringBuilder();
            if (rentable != null) {
                rentableString.append(plugin.msg(lm.General_Cost, rentable.cost, rentable.days) + "\n");
                rentableString.append(plugin.msg(lm.Rentable_AllowRenewing, rentable.AllowRenewing) + "\n");
                rentableString.append(plugin.msg(lm.Rentable_StayInMarket, rentable.StayInMarket) + "\n");
                rentableString.append(plugin.msg(lm.Rentable_AllowAutoPay, rentable.AllowAutoPay));
            }
            if (sender instanceof Player) {

                rm = new RawMessage();
                rm.addText(forRentMsg).addHover(rentableString.toString());
                rm.show(sender);
            } else
                plugin.msg(sender, forRentMsg);
        } else if (plugin.getConfigManager().enabledRentSystem() && plugin.getRentManager().isRented(areaname)) {
            String RentedMsg = plugin.msg(lm.Residence_RentedBy, plugin.getRentManager().getRentingPlayer(areaname));

            RentableLand rentable = plugin.getRentManager().getRentableLand(areaname);
            RentedLand rented = plugin.getRentManager().getRentedLand(areaname);

            StringBuilder rentableString = new StringBuilder();
            if (rented != null) {
                rentableString.append(plugin.msg(lm.Rent_Expire, GetTime.getTime(rented.endTime)) + "\n");
                if (rented.player.equals(sender.getName()) || resadmin || res.isOwner(sender))
                    rentableString.append((rented.AutoPay ? plugin.msg(lm.Rent_AutoPayTurnedOn) : plugin.msg(lm.Rent_AutoPayTurnedOff))
                        + "\n");
            }

            if (rentable != null) {
                rentableString.append(plugin.msg(lm.General_Cost, rentable.cost, rentable.days) + "\n");
                rentableString.append(plugin.msg(lm.Rentable_AllowRenewing, rentable.AllowRenewing) + "\n");
                rentableString.append(plugin.msg(lm.Rentable_StayInMarket, rentable.StayInMarket) + "\n");
                rentableString.append(plugin.msg(lm.Rentable_AllowAutoPay, rentable.AllowAutoPay));
            }

            if (sender instanceof Player) {

                rm = new RawMessage();
                rm.addText(RentedMsg).addHover(rentableString.toString());
                rm.show(sender);
            } else
                plugin.msg(sender, RentedMsg);
        } else if (plugin.getTransactionManager().isForSale(areaname)) {
            int amount = plugin.getTransactionManager().getSaleAmount(areaname);
            String SellMsg = plugin.msg(lm.Economy_LandForSale) + " " + amount;
            plugin.msg(sender, SellMsg);
        }

        plugin.msg(sender, lm.General_Separator);
    }

    public void printAreaPlayers(String areaname, CommandSender sender, int page) {
        ClaimedResidence res = this.getByName(areaname);
        if (res == null) {
            plugin.msg(sender, lm.Invalid_Residence);
            return;
        }

        areaname = res.getName();

        plugin.msg(sender, lm.General_Separator);

        ResidencePermissions perms = res.getPermissions();

        perms.listPlayers(sender, null, page);

        PageInfo pi = new PageInfo(10, perms.getPlayerFlags().size(), page) {
            @Override
            public Boolean pageChange(int page) {
                printAreaPlayers(res.getName(), sender, page);
                return null;
            }
        };
        pi.autoPagination(sender, "res info " + areaname + " -players", "-p:");
    }

    public void mirrorPerms(Player reqPlayer, String targetArea, String sourceArea, boolean resadmin) {
        ClaimedResidence reciever = this.getByName(targetArea);
        ClaimedResidence source = this.getByName(sourceArea);
        if (source == null || reciever == null) {
            plugin.msg(reqPlayer, lm.Invalid_Residence);
            return;
        }
        if (!resadmin) {
            if (!reciever.getPermissions().hasResidencePermission(reqPlayer, true) || !source.getPermissions().hasResidencePermission(reqPlayer, true)) {
                plugin.msg(reqPlayer, lm.General_NoPermission);
                return;
            }
        }
        reciever.getPermissions().applyTemplate(reqPlayer, source.getPermissions(), resadmin);
    }

    public Map<String, Object> save() {
        clearSaveChache();
        Map<String, Object> worldmap = new LinkedHashMap<>();
        for (String worldName : getWorldNames()) {
            Map<String, Object> resmap = new LinkedHashMap<>();
            for (Entry<String, ClaimedResidence> res : (new TreeMap<String, ClaimedResidence>(residences)).entrySet()) {
                if (!res.getValue().getWorld().equals(worldName))
                    continue;

                try {
                    resmap.put(res.getValue().getResidenceName(), res.getValue().save());
                } catch (Throwable ex) {
                    Bukkit.getConsoleSender().sendMessage(plugin.getPrefix() + ChatColor.RED + " Failed to save residence (" + res.getKey() + ")!");
                    Logger.getLogger(ResidenceManager.class.getName()).log(Level.SEVERE, null, ex);
                }
            }

            worldmap.put(worldName, resmap);
        }
        return worldmap;
    }

    private void clearSaveChache() {
        optimizeMessages.clear();
        optimizeFlags.clear();
    }

    // Optimizing save file
    HashMap<String, List<MinimizeMessages>> optimizeMessages = new HashMap<String, List<MinimizeMessages>>();
    HashMap<String, List<MinimizeFlags>> optimizeFlags = new HashMap<String, List<MinimizeFlags>>();

    public MinimizeMessages addMessageToTempCache(String world, String enter, String leave) {
        List<MinimizeMessages> ls = optimizeMessages.get(world);
        if (ls == null)
            ls = new ArrayList<MinimizeMessages>();
        for (MinimizeMessages one : ls) {
            if (!one.add(enter, leave))
                continue;
            return one;
        }
        MinimizeMessages m = new MinimizeMessages(ls.size() + 1, enter, leave);
        ls.add(m);
        optimizeMessages.put(world, ls);
        return m;
    }

    public HashMap<Integer, Object> getMessageCatch(String world) {
        HashMap<Integer, Object> t = new HashMap<Integer, Object>();
        List<MinimizeMessages> ls = optimizeMessages.get(world);
        if (ls == null)
            return null;
        for (MinimizeMessages one : ls) {
            Map<String, String> root = new HashMap<>();
            root.put("EnterMessage", one.getEnter());
            root.put("LeaveMessage", one.getLeave());
            t.put(one.getId(), root);
        }
        return t;
    }

    public MinimizeFlags addFlagsTempCache(String world, Map<String, Boolean> map) {
        if (world == null)
            return null;
        List<MinimizeFlags> ls = optimizeFlags.get(world);
        if (ls == null)
            ls = new ArrayList<MinimizeFlags>();
        for (MinimizeFlags one : ls) {
            if (!one.add(map))
                continue;
            return one;
        }
        MinimizeFlags m = new MinimizeFlags(ls.size() + 1, map);
        ls.add(m);
        optimizeFlags.put(world, ls);
        return m;
    }

    public HashMap<Integer, Object> getFlagsCatch(String world) {
        HashMap<Integer, Object> t = new HashMap<Integer, Object>();
        List<MinimizeFlags> ls = optimizeFlags.get(world);
        if (ls == null)
            return null;
        for (MinimizeFlags one : ls) {
            t.put(one.getId(), one.getFlags());
        }
        return t;
    }

    private void clearLoadChache() {
        cacheMessages.clear();
        cacheFlags.clear();
    }

    HashMap<String, HashMap<Integer, MinimizeMessages>> cacheMessages = new HashMap<String, HashMap<Integer, MinimizeMessages>>();
    HashMap<String, HashMap<Integer, MinimizeFlags>> cacheFlags = new HashMap<String, HashMap<Integer, MinimizeFlags>>();

    public HashMap<String, HashMap<Integer, MinimizeMessages>> getCacheMessages() {
        return cacheMessages;
    }

    public HashMap<String, HashMap<Integer, MinimizeFlags>> getCacheFlags() {
        return cacheFlags;
    }

    public String getChacheMessageEnter(String world, int id) {
        HashMap<Integer, MinimizeMessages> c = cacheMessages.get(world);
        if (c == null)
            return null;
        MinimizeMessages m = c.get(id);
        if (m == null)
            return null;
        return m.getEnter();
    }

    public String getChacheMessageLeave(String world, int id) {
        HashMap<Integer, MinimizeMessages> c = cacheMessages.get(world);
        if (c == null)
            return null;
        MinimizeMessages m = c.get(id);
        if (m == null)
            return null;
        return m.getLeave();
    }

    public Map<String, Boolean> getChacheFlags(String world, int id) {
        HashMap<Integer, MinimizeFlags> c = cacheFlags.get(world);
        if (c == null)
            return null;
        MinimizeFlags m = c.get(id);
        if (m == null)
            return null;
        return m.getFlags();
    }

    public Set<String> getWorldNames() {
        Set<String> worldnames = new HashSet<String>();
        File saveFolder = new File(plugin.dataFolder, "Save");
        try {
            File worldFolder = new File(saveFolder, "Worlds");
            if (plugin.getConfigManager().isLoadEveryWorld()) {
                for (File f : worldFolder.listFiles()) {
                    if (!f.isFile())
                        continue;
                    String name = f.getName();
                    if (!name.startsWith(Residence.saveFilePrefix))
                        continue;
                    worldnames.add(name.substring(Residence.saveFilePrefix.length(), name.length() - 4));
                }

            }
            plugin.getServ().getWorlds().forEach((w) -> {
                worldnames.add(w.getName());
            });
        } catch (Exception ex) {
            plugin.getServ().getWorlds().forEach((w) -> {
                worldnames.add(w.getName());
            });
            Logger.getLogger(Residence.class.getName()).log(Level.SEVERE, null, ex);
            throw ex;
        }
        return worldnames;
    }

    public void load(Map<String, Object> root) throws Exception {
        if (root == null)
            return;
        residences.clear();
        for (String worldName : getWorldNames()) {
            long time = System.currentTimeMillis();

            @SuppressWarnings("unchecked")
            Map<String, Object> reslist = (Map<String, Object>) root.get(worldName);
            if (!plugin.isDisabledWorld(worldName) && !plugin.getConfigManager().CleanerStartupLog)
                Bukkit.getConsoleSender().sendMessage(plugin.getPrefix() + " Loading " + worldName + " data into memory...");
            if (reslist != null) {
                try {
                    chunkResidences.put(worldName, multithreadLoadMap(worldName, reslist));
                } catch (Exception ex) {
                    Bukkit.getConsoleSender().sendMessage(plugin.getPrefix() + ChatColor.RED + "Error in loading save file for world: " + worldName);
                    if (plugin.getConfigManager().stopOnSaveError())
                        throw (ex);
                }
            }

            long pass = System.currentTimeMillis() - time;
            String PastTime = pass > 1000 ? String.format("%.2f", (pass / 1000F)) + " sec" : pass + " ms";

            if (!plugin.isDisabledWorld(worldName))
                Bukkit.getConsoleSender().sendMessage(plugin.getPrefix() + " Loaded " + worldName + " data into memory. (" + PastTime + ") -> " + (reslist == null ? "0" : reslist.size())
                    + " residences");
        }

        clearLoadChache();
    }

    int chunkCount = 0;

    public Map<ChunkRef, List<ClaimedResidence>> multithreadLoadMap(String worldName, Map<String, Object> root) throws InterruptedException, ExecutionException {
        Map<ChunkRef, List<ClaimedResidence>> retRes = new ConcurrentHashMap<>();

        if (root == null) {
            return retRes;
        }

        chunkCount = 0;

        int numCores = Runtime.getRuntime().availableProcessors();
        ExecutorService executorService = Executors.newFixedThreadPool(numCores);

        List<Future<Void>> futures = new ArrayList<>();
        int batchSize = (int) Math.ceil(root.entrySet().size() / (double) numCores);

        batchSize = CMINumber.clamp(batchSize, 500, root.entrySet().size());

        int i = 0;

        List<Entry<String, Object>> batch = new ArrayList<>();

        int total = root.entrySet().size() - 1;

        for (Entry<String, Object> entry : root.entrySet()) {
            batch.add(entry);

            if (batch.size() < batchSize && i < total) {
                i++;
                continue;
            }

            List<Entry<String, Object>> currentBatch = batch;
            batch = new ArrayList<>();
            i++;

            Future<Void> future = executorService.submit(() -> {
                for (Entry<String, Object> currentEntry : currentBatch) {
                    try {
                        ClaimedResidence residence = ClaimedResidence.load(worldName, (Map<String, Object>) currentEntry.getValue(), null, plugin);

                        if (residence == null) {
                            continue;
                        }

                        if (residence.getPermissions().getOwnerUUID().toString().equals(plugin.getServerLandUUID()) &&
                            !residence.getOwner().equalsIgnoreCase("Server land") &&
                            !residence.getOwner().equalsIgnoreCase(plugin.getServerLandName())) {
                            continue;
                        }

                        if (residence.getOwner().equalsIgnoreCase("Server land")) {
                            residence.getPermissions().setOwner(plugin.getServerLandName(), false);
                        }

                        String resName = currentEntry.getKey().toLowerCase();

                        int increment = getNameIncrement(resName);

                        if (residence.getResidenceName() == null)
                            residence.setName(currentEntry.getKey());

                        if (increment > 0) {
                            residence.setName(residence.getResidenceName() + increment);
                            resName += increment;
                        }

                        for (ChunkRef chunk : getChunks(residence)) {
                            retRes.compute(chunk, (k, v) -> {
                                if (v == null) {
                                    v = new ArrayList<>();
                                }
                                v.add(residence);
                                return v;
                            });
                        }

                        plugin.getPlayerManager().addResidence(residence.getOwner(), residence);

                        residences.put(resName, residence);
                    } catch (Exception ex) {
                        Bukkit.getConsoleSender().sendMessage(plugin.getPrefix() + ChatColor.RED + " Failed to load residence (" + currentEntry.getKey() + ")! Reason:" + ex.getMessage()
                            + " Error Log:");
                        Logger.getLogger(ResidenceManager.class.getName()).log(Level.SEVERE, null, ex);
                        if (plugin.getConfigManager().stopOnSaveError()) {
                            throw new RuntimeException(ex);
                        }
                    }
                }
                return null;
            });

            futures.add(future);
        }

        for (Future<Void> future : futures) {
            future.get();
        }

        executorService.shutdown();

        return retRes;
    }

    // Old method for single core loading
    public Map<ChunkRef, List<ClaimedResidence>> loadMap(String worldName, Map<String, Object> root) throws Exception {
        Map<ChunkRef, List<ClaimedResidence>> retRes = new HashMap<>();
        if (root == null)
            return retRes;

        int i = 0;
        int y = 0;
        for (Entry<String, Object> res : root.entrySet()) {
            if (i == 100 && plugin.getConfigManager().isUUIDConvertion())
                Bukkit.getConsoleSender().sendMessage(plugin.getPrefix() + " " + worldName + " UUID conversion done: " + y + " of " + root.size());
            if (i >= 100)
                i = 0;
            i++;
            y++;
            try {
                @SuppressWarnings("unchecked")
                ClaimedResidence residence = ClaimedResidence.load(worldName, (Map<String, Object>) res.getValue(), null, plugin);
                if (residence == null)
                    continue;

                if (residence.getPermissions().getOwnerUUID().toString().equals(plugin.getServerLandUUID()) &&
                    !residence.getOwner().equalsIgnoreCase("Server land") &&
                    !residence.getOwner().equalsIgnoreCase(plugin.getServerLandName()))
                    continue;

                if (residence.getOwner().equalsIgnoreCase("Server land")) {
                    residence.getPermissions().setOwner(plugin.getServerLandName(), false);
                }
                String resName = res.getKey().toLowerCase();

                // Checking for duplicated residence names and renaming them
                int increment = getNameIncrement(resName);

                if (residence.getResidenceName() == null)
                    residence.setName(res.getKey());

                if (increment > 0) {
                    residence.setName(residence.getResidenceName() + increment);
                    resName += increment;
                }

                for (ChunkRef chunk : getChunks(residence)) {
                    List<ClaimedResidence> ress = new ArrayList<>();
                    if (retRes.containsKey(chunk)) {
                        ress.addAll(retRes.get(chunk));
                    }
                    ress.add(residence);
                    retRes.put(chunk, ress);
                }

                plugin.getPlayerManager().addResidence(residence.getOwner(), residence);

                residences.put(resName.toLowerCase(), residence);

            } catch (Exception ex) {
                Bukkit.getConsoleSender().sendMessage(plugin.getPrefix() + ChatColor.RED + " Failed to load residence (" + res.getKey() + ")! Reason:" + ex.getMessage()
                    + " Error Log:");
                Logger.getLogger(ResidenceManager.class.getName()).log(Level.SEVERE, null, ex);
                if (plugin.getConfigManager().stopOnSaveError()) {
                    throw (ex);
                }
            }
        }

        return retRes;
    }

    private int getNameIncrement(String name) {
        String orName = name;
        int i = 0;
        while (i < 1000) {
            if (residences.containsKey(name.toLowerCase())) {
                i++;
                name = orName + i;
            } else
                break;
        }
        return i;
    }

    private static List<ChunkRef> getChunks(ClaimedResidence res) {
        List<ChunkRef> chunks = new ArrayList<>();
        res.getAreaMap().values().forEach(area -> chunks.addAll(area.getChunks()));
        return chunks;
    }

    public boolean renameResidence(String oldName, String newName) {
        return this.renameResidence(null, oldName, newName, true);
    }

    public boolean renameResidence(Player player, String oldName, String newName, boolean resadmin) {
        if (!ResPerm.rename.hasPermission(player, true)) {
            return false;
        }

        if (!plugin.validName(newName)) {
            plugin.msg(player, lm.Invalid_NameCharacters);
            return false;
        }
        ClaimedResidence res = this.getByName(oldName);
        if (res == null) {
            plugin.msg(player, lm.Invalid_Residence);
            return false;
        }

        if (res.getRaid().isRaidInitialized() && !resadmin) {
            plugin.msg(player, lm.Raid_cantDo);
            return false;
        }

        oldName = res.getName();
        if (res.getPermissions().hasResidencePermission(player, true) || resadmin) {
            if (res.getParent() == null) {
                if (residences.containsKey(newName.toLowerCase())) {
                    plugin.msg(player, lm.Residence_AlreadyExists, newName);
                    return false;
                }

                ResidenceRenameEvent resevent = new ResidenceRenameEvent(res, newName, oldName);
                plugin.getServ().getPluginManager().callEvent(resevent);
                
                if (resevent.isCancelled())
                    return false;
                
                newName = resevent.getNewResidenceName();
                
                removeChunkList(oldName);
                res.setName(newName);

                residences.put(newName.toLowerCase(), res);
                residences.remove(oldName.toLowerCase());

                calculateChunks(newName);

                plugin.getSignUtil().updateSignResName(res);

                plugin.msg(player, lm.Residence_Rename, oldName, newName);

                return true;
            }
            String[] oldname = oldName.split("\\.");
            ClaimedResidence parent = res.getParent();

            boolean feed = parent.renameSubzone(player, oldname[oldname.length - 1], newName, resadmin);

            plugin.getSignUtil().updateSignResName(res);

            return feed;
        }

        plugin.msg(player, lm.General_NoPermission);

        return false;
    }

    public void giveResidence(Player reqPlayer, String targPlayer, String residence, boolean resadmin) {
        giveResidence(reqPlayer, targPlayer, residence, resadmin, false);
    }

    public void giveResidence(Player reqPlayer, String targPlayer, String residence, boolean resadmin, boolean includeSubzones) {
        giveResidence(reqPlayer, targPlayer, getByName(residence), resadmin, includeSubzones);
    }

    public void giveResidence(Player reqPlayer, String targPlayer, ClaimedResidence res, boolean resadmin, boolean includeSubzones) {

        if (res == null) {
            plugin.msg(reqPlayer, lm.Invalid_Residence);
            return;
        }

        String residence = res.getName();

        if (!res.getPermissions().hasResidencePermission(reqPlayer, true) && !resadmin) {
            plugin.msg(reqPlayer, lm.General_NoPermission);
            return;
        }
        Player giveplayer = plugin.getServ().getPlayer(targPlayer);
        if (giveplayer == null || !giveplayer.isOnline()) {
            plugin.msg(reqPlayer, lm.General_NotOnline);
            return;
        }
        CuboidArea[] areas = res.getAreaArray();

        ResidencePlayer rPlayer = plugin.getPlayerManager().getResidencePlayer(giveplayer);
        PermissionGroup group = rPlayer.getGroup();

        if (areas.length > group.getMaxPhysicalPerResidence() && !resadmin) {
            plugin.msg(reqPlayer, lm.Residence_GiveLimits);
            return;
        }
        if (!hasMaxZones(giveplayer.getName(), rPlayer.getMaxRes()) && !resadmin) {
            plugin.msg(reqPlayer, lm.Residence_GiveLimits);
            return;
        }
        if (!resadmin) {
            for (CuboidArea area : areas) {
                if (!res.isSubzone() && !res.isSmallerThanMax(giveplayer, area, resadmin) || res.isSubzone() && !res.isSmallerThanMaxSubzone(giveplayer, area,
                    resadmin)) {
                    plugin.msg(reqPlayer, lm.Residence_GiveLimits);
                    return;
                }
            }
        }

        if (!res.getPermissions().setOwner(giveplayer, true))
            return;
        // Fix phrases here
        plugin.msg(reqPlayer, lm.Residence_Give, residence, giveplayer.getName());
        plugin.msg(giveplayer, lm.Residence_Recieve, residence, reqPlayer.getName());
        plugin.getSignUtil().updateSignResName(res);
        if (includeSubzones)
            for (ClaimedResidence one : res.getSubzones()) {
                giveResidence(reqPlayer, targPlayer, one, resadmin, includeSubzones);
            }
    }

    public void removeAllFromWorld(CommandSender sender, String world) {
        removeAllFromWorld(sender, world, null);
    }

    public void removeAllFromWorld(CommandSender sender, String world, List<String> playerExceptions) {
        int count = 0;
        Iterator<ClaimedResidence> it = residences.values().iterator();
        while (it.hasNext()) {
            ClaimedResidence next = it.next();

            if (next.getPermissions().getWorldName().equals(world)) {
                if (playerExceptions != null && !playerExceptions.isEmpty()) {
                    if (playerExceptions.contains(next.getOwner().toLowerCase()))
                        continue;

                    if (playerExceptions.contains(next.getOwnerUUID().toString()))
                        continue;
                }
                it.remove();
                count++;
            }
        }
        chunkResidences.remove(world);
        chunkResidences.put(world, new HashMap<ChunkRef, List<ClaimedResidence>>());
        if (count == 0) {
            sender.sendMessage(ChatColor.RED + "No residences found in world: " + ChatColor.YELLOW + world);
        } else {
            sender.sendMessage(ChatColor.RED + "Removed " + ChatColor.YELLOW + count + ChatColor.RED + " residences in world: " + ChatColor.YELLOW + world);
        }

//	plugin.getPlayerManager().fillList();
    }

    public int getResidenceCount() {
        return residences.size();
    }

    public Map<String, ClaimedResidence> getResidences() {
        return residences;
    }

    public void removeChunkList(String name) {
        if (name == null)
            return;
        name = name.toLowerCase();
        ClaimedResidence res = residences.get(name);
        if (res == null)
            return;
        String world = res.getWorld();
        if (chunkResidences.get(world) == null)
            return;
        for (ChunkRef chunk : getChunks(res)) {
            List<ClaimedResidence> ress = new ArrayList<>();
            if (chunkResidences.get(world).containsKey(chunk)) {
                ress.addAll(chunkResidences.get(world).get(chunk));
            }

            ress.remove(res);
            chunkResidences.get(world).put(chunk, ress);
        }

    }

    public void calculateChunks(String name) {
        if (name == null)
            return;
        name = name.toLowerCase();
        ClaimedResidence res = residences.get(name);
        if (res == null)
            return;
        String world = res.getWorld();
        if (chunkResidences.get(world) == null) {
            chunkResidences.put(world, new HashMap<ChunkRef, List<ClaimedResidence>>());
        }
        for (ChunkRef chunk : getChunks(res)) {
            List<ClaimedResidence> ress = new ArrayList<>();
            if (chunkResidences.get(world).containsKey(chunk)) {
                ress.addAll(chunkResidences.get(world).get(chunk));
            }
            ress.add(res);
            chunkResidences.get(world).put(chunk, ress);
        }
    }

    public static final class ChunkRef {

        public static int getChunkCoord(final int val) {
            // For more info, see CraftBukkit.CraftWorld.getChunkAt( Location )
            return val >> 4;
        }

        private final int z;
        private final int x;

        public ChunkRef(Location loc) {
            this.x = getChunkCoord(loc.getBlockX());
            this.z = getChunkCoord(loc.getBlockZ());
        }

        public ChunkRef(int x, int z) {
            this.x = x;
            this.z = z;
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            ChunkRef other = (ChunkRef) obj;
            return this.x == other.x && this.z == other.z;
        }

        @Override
        public int hashCode() {
            return x ^ z;
        }

        /**
         * Useful for debug
         * 
         * @return
         */
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("{ x: ").append(x).append(", z: ").append(z).append(" }");
            return sb.toString();
        }

        public int getZ() {
            return z;
        }

        public int getX() {
            return x;
        }
    }

}