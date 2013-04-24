//
// Copyright (c) 2012, J2 Innovations
// Licensed under the Academic Free License version 3.0
//
// History:
//   29 Mar 2013  Mike Jarmy  Creation
//
package nhaystack.server;

import java.util.*;
import javax.baja.control.*;
import javax.baja.history.*;
import javax.baja.log.*;
import javax.baja.naming.*;
import javax.baja.sys.*;
import javax.baja.util.*;

import haystack.*;
import nhaystack.*;
import nhaystack.collection.*;
import nhaystack.site.*;

/**
  * Cache stores various data structures that make it faster to look things up.
  */
public class Cache
{
    Cache(NHServer server)
    {
        this.server = server;
    }

    /**
      * Rebuild the cache.
      */
    synchronized void rebuild()
    {
        long t0 = Clock.ticks();
        LOG.message("Begin cache rebuild.");

        rebuildComponentCache();
        rebuildHistoryCache_firstPass();

        if (!initialized)
            initialized = true;

        // simplest way to avoid a chicken-and-egg problem
        // when creating the nav history cache
        rebuildHistoryCache_secondPass();

        numPoints = 0;
        numEquips = 0;
        numSites = 0;
        Iterator it = server.iterator();
        while (it.hasNext())
        {
            HDict tags = (HDict) it.next();
            if (tags.has("point")) numPoints++;
            else if (tags.has("equip")) numEquips++;
            else if (tags.has("site")) numSites++;
        }
        lastRebuildTime = BAbsTime.now();

        long t1 = Clock.ticks();
        LOG.message("End cache rebuild " + (t1-t0) + "ms.");
        lastRebuildDuration = BRelTime.make(t1-t0);
    }

    /**
      * Get the history config that goes with the remote point, or return null.
      */
    public synchronized BHistoryConfig getHistoryConfig(RemotePoint remotePoint)
    {
        if (!initialized) throw new IllegalStateException("Cache is not initialized.");

        return (BHistoryConfig) remoteToConfig.get(remotePoint);
    }

    /**
      * Get the control point that goes with the remote point, or return null.
      */
    public synchronized BControlPoint getControlPoint(RemotePoint remotePoint)
    {
        if (!initialized) throw new IllegalStateException("Cache is not initialized.");

        return (BControlPoint) remoteToPoint.get(remotePoint);
    }

    /**
      * Get everything that is tagged as 'site'
      */
    public BComponent[] getAllSites()
    {
        if (!initialized) throw new IllegalStateException("Cache is not initialized.");

        return sites;
    }

    /**
      * Get everything that is tagged as 'equip'
      */
    public BComponent[] getAllEquips()
    {
        if (!initialized) throw new IllegalStateException("Cache is not initialized.");

        return equips;
    }

    /**
      * Return the implicit 'equip' for the point, or null.
      */
    public BComponent getImplicitEquip(BControlPoint point)
    {
        if (!initialized) throw new IllegalStateException("Cache is not initialized.");

        return (BComponent) implicitEquips.get(point.getHandle());
    }

    /**
      * Get the site identified by the navId, or return null.
      */
    public BComponent getNavSite(String siteNav)
    {
        if (!initialized) throw new IllegalStateException("Cache is not initialized.");

        return (BComponent) siteNavs.get(siteNav);
    }

    /**
      * Get the equip identified by the navId, or return null.
      */
    public BComponent getNavEquip(String equipNav)
    {
        if (!initialized) throw new IllegalStateException("Cache is not initialized.");

        return (BComponent) equipNavs.get(equipNav);
    }

    /**
      * Get all the equips associated with the given site navId.
      */
    public BComponent[] getNavSiteEquips(String siteNav)
    {
        if (!initialized) throw new IllegalStateException("Cache is not initialized.");

        BComponent site = (BComponent) siteNavs.get(siteNav);

        Array arr = (Array) siteEquips.get(site.getHandle());
        return (arr == null) ?  
            new BComponent[0] : 
            (BComponent[]) arr.trim();
    }

    /**
      * Get all the points associated with the given equip navId.
      */
    public BControlPoint[] getNavEquipPoints(String equipNav)
    {
        if (!initialized) throw new IllegalStateException("Cache is not initialized.");

        BComponent equip = (BComponent) equipNavs.get(equipNav);

        Array arr = (Array) equipPoints.get(equip.getHandle());
        return (arr == null) ?  
            new BControlPoint[0] : 
            (BControlPoint[]) arr.trim();
    }

    /**
      * Get the site identified by the equip navId and point navName, or return null.
      */
    public BControlPoint getNavPoint(String equipNav, String pointNav)
    {
        // TODO we may want to make this more efficient eventually.
        BControlPoint[] points = getNavEquipPoints(equipNav);

        for (int i = 0; i < points.length; i++)
        {
            BControlPoint point = points[i];
            HDict tags = BHDict.findTagAnnotation(point);

            if (navName(point, tags).equals(pointNav))
                return point;
        }
        return null;
    }

    /**
      * Get the stationNames for nav histories
      */
    public String[] getNavHistoryStationNames()
    {
        Array arr = new Array(String.class, navHistories.keySet());
        return (String[]) arr.trim();
    }

    /**
      * Get the nav histories for the given stationName
      */
    public BHistoryConfig[] getNavHistories(String stationName)
    {
        Array arr = (Array) navHistories.get(stationName);

        if (arr == null) 
            throw new BajaRuntimeException(
                "No nav histories found for '" + stationName + "'");

        return (BHistoryConfig[]) arr.trim();
    }

////////////////////////////////////////////////////////////////
// private
////////////////////////////////////////////////////////////////

    private void rebuildComponentCache()
    {
        remoteToPoint  = new HashMap();
        implicitEquips = new HashMap();
        siteNavs  = new HashMap();
        equipNavs = new HashMap();
        siteEquips  = new HashMap();
        equipPoints = new HashMap();

        Array sitesArr = new Array(BComponent.class);
        Array equipsArr = new Array(BComponent.class);

        Iterator iterator = new ComponentTreeIterator(
            (BComponent) BOrd.make("slot:/").resolve(server.getService(), null).get());

        while (iterator.hasNext())
        {
            BComponent comp = (BComponent) iterator.next();
            HDict tags = BHDict.findTagAnnotation(comp);

            // point
            if (comp instanceof BControlPoint)
            {
                BControlPoint point = (BControlPoint) comp;

                // save remote point 
                if (point.getProxyExt().getType().is(RemotePoint.NIAGARA_PROXY_EXT)) 
                {
                    RemotePoint remote = RemotePoint.fromControlPoint(point);
                    if (remote != null) remoteToPoint.put(remote, point);
                }

                // explicit equip
                if (tags.has("equipRef"))
                {
                    HRef ref = tags.getRef("equipRef");
                    BComponent equip = server.lookupComponent(ref);
                    addBackwardsEquipPoint(equip, point);
                }
                // implicit equip
                else
                {
                    BComponent equip = findImplicitEquip(point);
                    if (equip != null)
                    {
                        addBackwardsEquipPoint(equip, point);
                        implicitEquips.put(point.getHandle(), equip);
                    }
                }
            }
            // auto-tagged site and equip
            else if (comp instanceof BHTagged)
            {
                if (comp instanceof BHSite)
                {
                    sitesArr.add(comp);
                    siteNavs.put(
                        Nav.makeSiteNavId(
                            navName(comp, tags)),
                        comp);
                }
                else if (comp instanceof BHEquip)
                {
                    equipsArr.add(comp);
                    processEquip(comp);
                }
            }
            // implicit equip
            else
            {
                if (tags.has("equip"))
                {
                    equipsArr.add(comp);
                    processEquip(comp);
                }
            }
        }

        sites  = (BComponent[]) sitesArr.trim();
        equips = (BComponent[]) equipsArr.trim();
    }

    /**
      * This will save a reference to the point from its equip.
      */
    private void addBackwardsEquipPoint(BComponent equip, BControlPoint point)
    {
        Array arr = (Array) equipPoints.get(equip.getHandle());
        if (arr == null)
            equipPoints.put(equip.getHandle(), arr = new Array(BControlPoint.class));
        arr.add(point);
    }

    /**
      * This will save a reference to the equip from its site.
      */
    private void addBackwardsSiteEquip(BComponent site, BComponent equip)
    {
        Array arr = (Array) siteEquips.get(site.getHandle());
        if (arr == null)
            siteEquips.put(site.getHandle(), arr = new Array(BComponent.class));
        arr.add(equip);
    }

    private void processEquip(BComponent equip)
    {
        HDict equipTags = BHDict.findTagAnnotation(equip);
        if (equipTags.has("siteRef"))
        {
            HRef ref = equipTags.getRef("siteRef");
            BComponent site = server.lookupComponent(ref);
            if (site != null)
            {
                // add backwards reference
                addBackwardsSiteEquip(site, equip);

                // save the equip nav 
                HDict siteTags = BHDict.findTagAnnotation(site);
                equipNavs.put(
                    Nav.makeEquipNavId(
                        navName(site, siteTags),
                        navName(equip, equipTags)),
                    equip);
            }
        }
    }

    private void rebuildHistoryCache_firstPass()
    {
        remoteToConfig = new HashMap();
        navHistories = new TreeMap();

        BIHistory[] histories = server.getService().getHistoryDb().getHistories(); 
        for (int i = 0; i < histories.length; i++)
        {
            BIHistory h = histories[i];
            BHistoryId hid = h.getId();

            // ignore local histories
            if (hid.getDeviceName().equals(Sys.getStation().getStationName()))
                continue;

            BHistoryConfig cfg = h.getConfig();
            RemotePoint remotePoint = RemotePoint.fromHistoryConfig(cfg);
            if (remotePoint != null)
                remoteToConfig.put(remotePoint, cfg);
        }
    }

    private void rebuildHistoryCache_secondPass()
    {
        Iterator itr = new HistoryDbIterator(server.getService().getHistoryDb());
        while (itr.hasNext())
        {
            BHistoryConfig cfg = (BHistoryConfig) itr.next();

            if (server.getHistoryStorehouse().isVisibleHistory(cfg))
            {
                String stationName = cfg.getId().getDeviceName();

                Array arr = (Array) navHistories.get(stationName);
                if (arr == null)
                    navHistories.put(stationName, arr = new Array(BHistoryConfig.class));
                arr.add(cfg);
            }
        }
    }

    /**
      * Find a parent device that has a BHEquip child
      */
    private BComponent findImplicitEquip(BControlPoint point)
    {
        BComponent parent = (BComponent) point.getParent();

        while (true)
        {
            if (parent == null) return null;

            HDict tags = BHDict.findTagAnnotation(parent);
            if (tags.has("equip"))
                return parent;

            parent = (BComponent) parent.getParent();
        }
    }

    private static String navName(BComponent comp, HDict tags)
    {
        String format = tags.has("navNameFormat") ?
            tags.getStr("navNameFormat") :
            "%displayName%";

        return BFormat.format(format, comp);
    }

////////////////////////////////////////////////////////////////
// attribs
////////////////////////////////////////////////////////////////

    private static final Log LOG = Log.getLog("nhaystack");

    private final NHServer server;
    private boolean initialized = false;

    private Map remoteToConfig = null; // RemotePoint -> BHistoryConfig
    private Map navHistories = null;   // stationName -> Array<BHistoryConfig>

    private Map remoteToPoint   = null; // RemotePoint -> BControlPoint
    private BComponent[] sites  = null;
    private BComponent[] equips = null;
    private Map implicitEquips  = null; // Handle -> BComponent
    private Map siteNavs  = null; // String -> BComponent
    private Map equipNavs = null; // String -> BComponent
    private Map siteEquips  = null; // Handle -> Array<BComponent>
    private Map equipPoints = null; // Handle -> Array<BControlPoint>

    int numPoints = 0;
    int numEquips = 0;
    int numSites = 0;
    BRelTime lastRebuildDuration = BRelTime.DEFAULT;
    BAbsTime lastRebuildTime = BAbsTime.DEFAULT;
}

