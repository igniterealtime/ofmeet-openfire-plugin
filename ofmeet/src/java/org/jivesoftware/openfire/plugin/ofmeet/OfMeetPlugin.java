/*
 * $Revision $
 * $Date $
 *
 * Copyright (C) 2005-2010 Jive Software. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jivesoftware.openfire.plugin.ofmeet;

import org.dom4j.Element;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.cluster.ClusterEventListener;
import org.jivesoftware.openfire.cluster.ClusterManager;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.event.SessionEventDispatcher;
import org.jivesoftware.openfire.event.SessionEventListener;
import org.jivesoftware.openfire.interceptor.InterceptorManager;
import org.jivesoftware.openfire.net.SASLAuthentication;
import org.jivesoftware.openfire.plugin.ofmeet.jetty.OfMeetAzure;
import org.jivesoftware.openfire.plugin.ofmeet.sasl.OfMeetSaslProvider;
import org.jivesoftware.openfire.plugin.ofmeet.sasl.OfMeetSaslServer;
import org.jivesoftware.openfire.plugin.ofmeet.videobridge.JvbPluginWrapper;
import org.jivesoftware.openfire.session.ClientSession;
import org.jivesoftware.openfire.session.Session;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.PropertyEventDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.IQ;

import java.io.File;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Security;
import java.util.Arrays;
import java.util.List;

/**
 * Bundles various Jitsi components into one, standalone Openfire plugin.
 */
public class OfMeetPlugin implements Plugin, SessionEventListener, ClusterEventListener
{
    private static final Logger Log = LoggerFactory.getLogger(OfMeetPlugin.class);

    public boolean restartNeeded = false;

    private PluginManager manager;
    public File pluginDirectory;

    private WebappWrapper webappWrapper;
    private BookmarkInterceptor bookmarkInterceptor;

    private final JvbPluginWrapper jvbPluginWrapper;
    private final MeetingPlanner meetingPlanner;

    public OfMeetPlugin()
    {
        jvbPluginWrapper = new JvbPluginWrapper();
        meetingPlanner = new MeetingPlanner();
    }

    public String getName()
    {
        return "ofmeet";
    }

    public String getDescription()
    {
        return "OfMeet Plugin";
    }

    public void initializePlugin(final PluginManager manager, final File pluginDirectory)
    {
        this.manager = manager;
        this.pluginDirectory = pluginDirectory;

        // Initialize all Jitsi software, which provided the video-conferencing functionality.
        try
        {
            System.setProperty( "net.java.sip.communicator.SC_HOME_DIR_LOCATION",  pluginDirectory.getAbsolutePath() );
            System.setProperty( "net.java.sip.communicator.SC_HOME_DIR_NAME",      "." );
            System.setProperty( "net.java.sip.communicator.SC_CACHE_DIR_LOCATION", pluginDirectory.getAbsolutePath() );
            System.setProperty( "net.java.sip.communicator.SC_LOG_DIR_LOCATION",   pluginDirectory.getAbsolutePath() );

            jvbPluginWrapper.initialize( manager, pluginDirectory );
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while attempting to initialize the Jitsi components.", ex );
        }

        // Initialize our own additional functionality providers.
        try
        {
            meetingPlanner.initialize();
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while attempting to initialize the Meeting Planner.", ex );
        }

        try
        {
            webappWrapper = new WebappWrapper( manager, pluginDirectory );
            webappWrapper.initialize();
            PropertyEventDispatcher.addListener( webappWrapper );
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while attempting to load the public web application.", ex );
        }

        try
        {
            ClusterManager.addListener(this);

            Log.info("OfMeet Plugin - Initialize email listener");

            checkDownloadFolder(pluginDirectory);

            Log.info("OfMeet Plugin - Initialize IQ handler ");

            if ( JiveGlobals.getBooleanProperty( "ofmeet.bookmarks.auto-enable", true ) )
            {
                bookmarkInterceptor = new BookmarkInterceptor( this );
                InterceptorManager.getInstance().addInterceptor( bookmarkInterceptor );
            }

            SessionEventDispatcher.addListener(this);
        }
        catch (Exception e) {
            Log.error("Could NOT start open fire meetings", e);
        }

        Security.addProvider( new OfMeetSaslProvider() );
        SASLAuthentication.addSupportedMechanism( OfMeetSaslServer.MECHANISM_NAME );
    }

    public void destroyPlugin()
    {
        try
        {
            if ( webappWrapper != null )
            {
                PropertyEventDispatcher.removeListener( webappWrapper );
                webappWrapper.destroy();
            }
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while trying to unload the public web application of OFMeet.", ex );
        }

        try
        {
            SASLAuthentication.removeSupportedMechanism( OfMeetSaslServer.MECHANISM_NAME );
            Security.removeProvider( OfMeetSaslProvider.NAME );
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while trying to remove support for the OfMeet-specific SASL support.", ex );
        }

        try
        {
            meetingPlanner.destroy();
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while trying to destroy the Meeting Planner", ex );
        }

        try
        {
            SessionEventDispatcher.removeListener(this);
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while trying to destroy the OFMeet IQ Handler.", ex );
        }

        try
        {
            jvbPluginWrapper.destroy();
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while trying to destroy the Jitsi Videobridge plugin wrapper.", ex );
        }

        ClusterManager.removeListener(this);

        if ( bookmarkInterceptor != null )
        {
            InterceptorManager.getInstance().removeInterceptor( bookmarkInterceptor );
            bookmarkInterceptor = null;
        }
    }


    /**
     * Jitsi takes most of its configuration through system properties. This method sets these
     * properties, using values defined in JiveGlobals.
     */
    public void populateJitsiSystemPropertiesWithJivePropertyValues()
    {
        if ( jvbPluginWrapper != null )
        {
            jvbPluginWrapper.populateJitsiSystemPropertiesWithJivePropertyValues();
        }
    }

    private void checkDownloadFolder(File pluginDirectory)
    {
        String ofmeetHome = JiveGlobals.getHomeDirectory() + File.separator + "resources" + File.separator + "spank" + File.separator + "ofmeet-cdn";

        try
        {
            File ofmeetFolderPath = new File(ofmeetHome);

            if(!ofmeetFolderPath.exists())
            {
                ofmeetFolderPath.mkdirs();
            }

            List<String> lines = Arrays.asList("Move on, nothing here....");
            Path file = Paths.get(ofmeetHome + File.separator + "index.html");
            Files.write(file, lines, Charset.forName("UTF-8"));

            File downloadHome = new File(ofmeetHome + File.separator + "download");

            if(!downloadHome.exists())
            {
                downloadHome.mkdirs();
            }

            lines = Arrays.asList("Move on, nothing here....");
            file = Paths.get(downloadHome + File.separator + "index.html");
            Files.write(file, lines, Charset.forName("UTF-8"));
        }
        catch (Exception e)
        {
            Log.error("checkDownloadFolder", e);
        }
    }

    public URL getWebappURL() {
        return webappWrapper == null ? null : webappWrapper.getWebappURL();
    }

    //-------------------------------------------------------
    //
    //      clustering
    //
    //-------------------------------------------------------

    @Override
    public void joinedCluster()
    {
        Log.info("OfMeet Plugin - joinedCluster");
        try
        {
            jvbPluginWrapper.destroy();
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while trying to destroy the Jitsi Plugin.", ex );
        }
    }

    @Override
    public void joinedCluster(byte[] arg0)
    {
    }

    @Override
    public void leftCluster()
    {
        Log.info("OfMeet Plugin - leftCluster");
        try
        {
            jvbPluginWrapper.initialize( manager, pluginDirectory );
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while trying to initialize the Jitsi Plugin.", ex );
        }
    }

    @Override
    public void leftCluster(byte[] arg0)
    {
    }

    @Override
    public void markedAsSeniorClusterMember()
    {
        Log.info("OfMeet Plugin - markedAsSeniorClusterMember");
        try
        {
            jvbPluginWrapper.initialize( manager, pluginDirectory );
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while trying to initialize the Jitsi Plugin.", ex );
        }
    }

    //-------------------------------------------------------
    //
    //      session management
    //
    //-------------------------------------------------------

    public void anonymousSessionCreated(Session session)
    {
        Log.debug("OfMeet Plugin -  anonymousSessionCreated "+ session.getAddress().toString() + "\n" + ((ClientSession) session).getPresence().toXML());
    }

    public void anonymousSessionDestroyed(Session session)
    {
        Log.debug("OfMeet Plugin -  anonymousSessionDestroyed "+ session.getAddress().toString() + "\n" + ((ClientSession) session).getPresence().toXML());
    }

    public void resourceBound(Session session)
    {
        Log.debug("OfMeet Plugin -  resourceBound "+ session.getAddress().toString() + "\n" + ((ClientSession) session).getPresence().toXML());
    }

    public void sessionCreated(Session session)
    {
        Log.debug("OfMeet Plugin -  sessionCreated "+ session.getAddress().toString() + "\n" + ((ClientSession) session).getPresence().toXML());
    }

    public void sessionDestroyed(Session session)
    {
        Log.debug("OfMeet Plugin -  sessionDestroyed "+ session.getAddress().toString() + "\n" + ((ClientSession) session).getPresence().toXML());

        boolean skypeAvailable = XMPPServer.getInstance().getPluginManager().getPlugin("ofskype") != null;

        if (OfMeetAzure.skypeids.containsKey(session.getAddress().getNode()))
        {
            String sipuri = OfMeetAzure.skypeids.remove(session.getAddress().getNode());

            IQ iq = new IQ(IQ.Type.set);
            iq.setFrom(session.getAddress());
            iq.setTo(XMPPServer.getInstance().getServerInfo().getXMPPDomain());

            Element child = iq.setChildElement("request", "http://igniterealtime.org/protocol/ofskype");
            child.setText("{'action':'stop_skype_user', 'sipuri':'" + sipuri + "'}");
            XMPPServer.getInstance().getIQRouter().route(iq);

            Log.info("OfMeet Plugin - closing skype session " + sipuri);
        }
    }
}
