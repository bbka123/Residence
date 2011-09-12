/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package com.bekvon.bukkit.residence.protection;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.economy.EconomyInterface;
import com.bekvon.bukkit.residence.event.ResidenceDeleteEvent;
import com.bekvon.bukkit.residence.event.ResidenceDeleteEvent.DeleteCause;
import com.bekvon.bukkit.residence.permissions.PermissionGroup;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.bukkit.entity.Player;

/**
 *
 * @author Administrator
 */
public class LeaseManager {

    private Map<String,Long> leaseExpireTime;

    ResidenceManager manager;

    public LeaseManager(ResidenceManager m)
    {
        manager = m;
        leaseExpireTime = Collections.synchronizedMap(new HashMap<String,Long>());
    }

    public boolean leaseExpires(String area)
    {
        return leaseExpireTime.containsKey(area);
    }

    public Date getExpireTime(String area)
    {
        if(leaseExpireTime.containsKey(area))
        {
            return new Date(leaseExpireTime.get(area));
        }
        return null;
    }

    public void removeExpireTime(String area)
    {
        leaseExpireTime.remove(area);
    }

    public void setExpireTime(String area, int days)    
    {
        this.setExpireTime(null, area, days);
    }

    public void setExpireTime(Player player, String area, int days)
    {
        area = area.replace(".", "_");
        if(manager.getByName(area)!=null)
        {
           leaseExpireTime.put(area, daysToMs(days) + System.currentTimeMillis());
           if(player!=null)
                player.sendMessage("§a"+Residence.getLanguage().getPhrase("LeaseRenew", getExpireTime(area).toString()));
        }
        else
        {
            if(player!=null)
                player.sendMessage("§c"+Residence.getLanguage().getPhrase("InvalidArea"));
        }
    }

    public void renewArea(String area, Player player)
    {
        if(!leaseExpires(area))
        {
            player.sendMessage("§c"+Residence.getLanguage().getPhrase("LeaseNotExpire"));
            return;
        }
        PermissionGroup limits = Residence.getPermissionManager().getGroup(player);
        int max = limits.getMaxLeaseTime();
        int add = limits.getLeaseGiveTime();
        int rem = daysRemaining(area);
        EconomyInterface econ = Residence.getEconomyManager();
        if(econ!=null)
        {
            double cost = limits.getLeaseRenewCost();
            ClaimedResidence res = manager.getByName(area);
            int amount = (int) Math.ceil((double)res.getTotalSize() * cost);
            if(cost!=0D)
            {
                //Account account = iConomy.getBank().getAccount(player.getName());
                if(econ.canAfford(player.getName(), amount)/*account.hasEnough(amount)*/)
                {
                    econ.subtract(player.getName(), amount);
                    econ.add("Lease Money", amount);
                    player.sendMessage("§a"+Residence.getLanguage().getPhrase("MoneyCharged","§e" + amount+"§a.§e" + econ.getName() + "§a"));
                }
                else
                {
                    player.sendMessage("§c"+Residence.getLanguage().getPhrase("NotEnoughMoney"));
                    return;
                }
            }
        }
        if(rem+add>max)
        {
            setExpireTime(player,area,max);
            player.sendMessage("§6"+Residence.getLanguage().getPhrase("LeaseRenewMax"));
            player.sendMessage("§e"+Residence.getLanguage().getPhrase("LeaseRenew","§a" + getExpireTime(area))+"§e");
            return;
        }
        Long get = leaseExpireTime.get(area);
        if(get!=null)
        {
            get = get + daysToMs(add);
            leaseExpireTime.put(area, get);
        }
        else
            leaseExpireTime.put(area, daysToMs(add));
        player.sendMessage("§e"+Residence.getLanguage().getPhrase("LeaseRenew","§a" + getExpireTime(area)));
    }

    public int getRenewCost(ClaimedResidence res)
    {
        PermissionGroup limits = Residence.getPermissionManager().getGroup(res.getPermissions().getOwner(), res.getPermissions().getWorld());
        double cost = limits.getLeaseRenewCost();
        int amount = (int) Math.ceil((double)res.getTotalSize() * cost);
        return amount;
    }

    private long daysToMs(int days)
    {
        return (((long)days) * 24L * 60L * 60L * 1000L);
    }

    private int msToDays(long ms)
    {
        return (int) Math.ceil(((((double)ms/1000D)/60D)/60D)/24D);
    }

    private int daysRemaining(String area)
    {
        Long get = leaseExpireTime.get(area);
        if(get<=System.currentTimeMillis())
            return 0;
        return msToDays((int)(get-System.currentTimeMillis()));
    }

    public void doExpirations() {
        Set<Entry<String, Long>> set = leaseExpireTime.entrySet();
        Iterator<Entry<String, Long>> it = set.iterator();
        while (it.hasNext()) {
            Entry<String, Long> next = it.next();
            if (next.getValue() <= System.currentTimeMillis()) {
                String resname = next.getKey();
                ClaimedResidence res = Residence.getResidenceManager().getByName(resname);
                if (res == null) {
                    it.remove();
                } else {
                    boolean renewed = false;
                    String owner = res.getPermissions().getOwner();
                    PermissionGroup limits = Residence.getPermissionManager().getGroup(owner, res.getPermissions().getWorld());
                    int cost = this.getRenewCost(res);
                    if (Residence.getConfig().enableEconomy() && Residence.getConfig().autoRenewLeases()) {
                        if (cost == 0) {
                            renewed = true;
                        } else if (Residence.getEconomyManager().canAfford(owner, cost)) {
                            if (Residence.getEconomyManager().subtract(owner, cost)) {
                                renewed = true;
                            }
                        } else if (res.getBank().hasEnough(cost)) {
                            res.getBank().subtract(cost);
                            renewed = true;
                        }
                    }
                    if (!renewed) {
                        if (!Residence.getConfig().enabledRentSystem() || !Residence.getRentManager().isRented(resname)) {
                            ResidenceDeleteEvent resevent = new ResidenceDeleteEvent(null, res, DeleteCause.LEASE_EXPIRE);
                            Residence.getServ().getPluginManager().callEvent(resevent);
                            if (!resevent.isCancelled()) {
                                manager.removeResidence(next.getKey());
                                it.remove();
                            }
                        }
                    } else {
                        if (Residence.getConfig().enableEconomy() && Residence.getConfig().enableLeaseMoneyAccount()) {
                            Residence.getEconomyManager().add("Lease Money", cost);
                        }
                        next.setValue(System.currentTimeMillis() + daysToMs(limits.getLeaseGiveTime()));
                    }
                }
            }
        }
    }

    public void resetLeases()
    {
        leaseExpireTime.clear();
        String[] list = manager.getResidenceList();
        for(int i = 0; i < list.length; i++)
        {
            if(list[i]!=null)
            {
                ClaimedResidence res = Residence.getResidenceManager().getByName(list[i]);
                PermissionGroup group = Residence.getPermissionManager().getGroup(res.getPermissions().getOwner(),res.getPermissions().getWorld());
                this.setExpireTime(null,list[i], group.getLeaseGiveTime());
            }
        }
        System.out.println("[Residence] - Set default leases.");
    }

    public Map<String,Long> save()
    {
        return leaseExpireTime;
    }

    public void updateLeaseName(String oldName, String newName)
    {
        if(leaseExpireTime.containsKey(oldName))
        {
            leaseExpireTime.put(newName, leaseExpireTime.get(oldName));
            leaseExpireTime.remove(oldName);
        }
    }

    public static LeaseManager load(Map root,ResidenceManager m)
    {
        LeaseManager l = new LeaseManager(m);
        if(root!=null)
        {
            for(Object val : root.values())
            {
                if(!(val instanceof Long))
                {
                    root.remove(val);
                }
            }
            l.leaseExpireTime = Collections.synchronizedMap(root);
        }
        return l;
    }
}
