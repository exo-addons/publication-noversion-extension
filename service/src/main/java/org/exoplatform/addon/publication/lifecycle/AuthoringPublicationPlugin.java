package org.exoplatform.addon.publication.lifecycle;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import javax.jcr.version.Version;
import javax.jcr.version.VersionHistory;
import javax.jcr.version.VersionIterator;
import javax.portlet.PortletMode;

import org.apache.commons.lang.StringUtils;
import org.exoplatform.ecm.webui.utils.Utils;
import org.exoplatform.portal.application.PortalRequestContext;
import org.exoplatform.portal.config.UserPortalConfig;
import org.exoplatform.portal.config.UserPortalConfigService;
import org.exoplatform.portal.config.model.Page;
import org.exoplatform.portal.mop.navigation.Scope;
import org.exoplatform.portal.mop.user.UserNavigation;
import org.exoplatform.portal.mop.user.UserNode;
import org.exoplatform.portal.mop.user.UserPortal;
import org.exoplatform.portal.webui.util.Util;
import org.exoplatform.services.cms.CmsService;
import org.exoplatform.services.cms.jcrext.activity.ActivityCommonService;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.resources.ResourceBundleService;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.wcm.extensions.publication.impl.PublicationManagerImpl;
import org.exoplatform.services.wcm.extensions.publication.lifecycle.authoring.AuthoringPublicationConstant;
import org.exoplatform.services.wcm.extensions.publication.lifecycle.authoring.ui.UIPublicationContainer;
import org.exoplatform.services.wcm.extensions.publication.lifecycle.impl.LifecyclesConfig.Lifecycle;
import org.exoplatform.services.wcm.extensions.publication.lifecycle.impl.LifecyclesConfig.State;
import org.exoplatform.services.wcm.publication.PublicationDefaultStates;
import org.exoplatform.services.wcm.publication.PublicationUtil;
import org.exoplatform.services.wcm.publication.WCMComposer;
import org.exoplatform.services.wcm.publication.WCMPublicationService;
import org.exoplatform.services.wcm.publication.WebpagePublicationPlugin;
import org.exoplatform.services.wcm.publication.lifecycle.stageversion.config.VersionData;
import org.exoplatform.services.wcm.publication.lifecycle.stageversion.config.VersionLog;
import org.exoplatform.services.wcm.utils.WCMCoreUtils;
import org.exoplatform.webui.core.UIComponent;
import org.exoplatform.webui.form.UIForm;

/**
 * 
 * @author boubaker.khanfir@exoplatform.com
 * 
 */
public class AuthoringPublicationPlugin extends WebpagePublicationPlugin {

  private static final Log LOG = ExoLogger.getLogger(AuthoringPublicationPlugin.class.getName());
  private static final String PUBLICATION_LIFECYCLE_PROP = "publication:lifecycle";

  private ListenerService listenerService;
  private ActivityCommonService activityService;
  private ResourceBundleService resourceBundleService;

  // computed in Getters
  private PublicationManagerImpl publicationManagerImpl;
  private WCMPublicationService wcmPublicationService;

  public AuthoringPublicationPlugin(ListenerService listenerService, ActivityCommonService activityCommonService, ResourceBundleService resourceBundleService) {
    this.listenerService = listenerService;
    this.activityService = activityCommonService;
    this.resourceBundleService = resourceBundleService;
  }

  public void changeState(Node node, String newState, HashMap<String, String> context) throws Exception {
    LOG.info("Change state of'" + node.getPath() + "' to " + newState);

    // Add mixin mix:versionable
    if (node.canAddMixin(Utils.MIX_VERSIONABLE)) {
      node.addMixin(Utils.MIX_VERSIONABLE);
      node.save();
    }

    String versionName = context.get(AuthoringPublicationConstant.CURRENT_REVISION_NAME);
    String logItemName = StringUtils.isEmpty(versionName) ? node.getName() : versionName;
    String userId = getCurrenUserName(node);

    Map<String, VersionData> revisionsMap = getRevisionData(node);

    if (PublicationDefaultStates.PENDING.equals(newState) || PublicationDefaultStates.APPROVED.equals(newState) || PublicationDefaultStates.STAGED.equals(newState)
        || PublicationDefaultStates.ENROLLED.equalsIgnoreCase(newState) || PublicationDefaultStates.UNPUBLISHED.equalsIgnoreCase(newState)
        || PublicationDefaultStates.OBSOLETE.equalsIgnoreCase(newState)) {
      node.setProperty(AuthoringPublicationConstant.CURRENT_STATE, newState);

      // add log
      VersionLog versionLog = new VersionLog(logItemName, newState, userId, GregorianCalendar.getInstance(), getAuthoringPublicationConstant(newState));
      addLog(node, versionLog);

      // add revision data
      addRevisionData(node, node.getUUID(), revisionsMap, userId, newState);
    } else if (PublicationDefaultStates.ARCHIVED.equalsIgnoreCase(newState)) {
      // change base version to archived state
      node.setProperty(AuthoringPublicationConstant.CURRENT_STATE, PublicationDefaultStates.ARCHIVED);
      node.setProperty(AuthoringPublicationConstant.LIVE_REVISION_PROP, "");

      // add log
      VersionLog versionLog = new VersionLog(node.getName(), PublicationDefaultStates.ARCHIVED, userId, new GregorianCalendar(), AuthoringPublicationConstant.CHANGE_TO_ARCHIVED);
      addLog(node, versionLog);

      // add revision data
      addRevisionData(node, node.getUUID(), revisionsMap, userId, newState);
    } else if (PublicationDefaultStates.DRAFT.equalsIgnoreCase(newState)) {
      // change base version to DRAFT state
      node.setProperty(AuthoringPublicationConstant.CURRENT_STATE, newState);
      node.save();

      VersionData editableRevision = revisionsMap.get(node.getUUID());
      if (editableRevision != null) {
        String lifecycleName = node.getProperty(PUBLICATION_LIFECYCLE_PROP).getString();
        Lifecycle lifecycle = getPublicationManagerImpl().getLifecycle(lifecycleName);
        List<State> states = lifecycle.getStates();
        if (states == null || states.size() <= 0) {
          editableRevision.setState(PublicationDefaultStates.ENROLLED);
        } else {
          editableRevision.setState(states.get(0).getState());
        }
        editableRevision.setAuthor(userId);
      } else {
        editableRevision = new VersionData(node.getUUID(), PublicationDefaultStates.ENROLLED, userId);
      }
      revisionsMap.put(node.getUUID(), editableRevision);
      addRevisionData(node, node.getUUID(), revisionsMap, userId, newState);

      VersionLog versionLog = new VersionLog(node.getBaseVersion().getName(), newState, userId, new GregorianCalendar(), AuthoringPublicationConstant.CHANGE_TO_DRAFT);
      addLog(node, versionLog);
    } else if (PublicationDefaultStates.PUBLISHED.equals(newState)) {
      revisionsMap.clear();

      node.setProperty(AuthoringPublicationConstant.LIVE_REVISION_PROP, "");
      node.setProperty(AuthoringPublicationConstant.LIVE_DATE_PROP, new GregorianCalendar());
      node.setProperty(AuthoringPublicationConstant.CURRENT_STATE, PublicationDefaultStates.PUBLISHED);
      node.save();
      node.refresh(false);

      deleteVersions(node);

      // Make a version and copy the current published content
      if (!node.isCheckedOut()) {
        node.checkout();
      }
      Version liveVersion = node.checkin();
      node.checkout();
      node.refresh(false);

      // add Version revision data
      addRevisionData(node, liveVersion.getUUID(), revisionsMap, userId, newState);

      node.setProperty(AuthoringPublicationConstant.LIVE_REVISION_PROP, liveVersion.getUUID());
      node.save();

      // Add log entry
      VersionLog versionLog = new VersionLog(liveVersion.getName(), newState, userId, new GregorianCalendar(), getAuthoringPublicationConstant(newState));
      addLog(node, versionLog);

      // add revision data
      addRevisionData(node, node.getUUID(), revisionsMap, userId, newState);
    }

    node.setProperty("publication:lastUser", userId);
    if (!node.isNew()) {
      node.save();
    }
    // raise event to notify that state is changed
    if (!PublicationDefaultStates.ENROLLED.equalsIgnoreCase(newState)) {
      CmsService cmsService = WCMCoreUtils.getService(CmsService.class);
      if ("true".equalsIgnoreCase(context.get(AuthoringPublicationConstant.IS_INITIAL_PHASE))) {
        listenerService.broadcast(AuthoringPublicationConstant.POST_INIT_STATE_EVENT, cmsService, node);
      } else {
        listenerService.broadcast(AuthoringPublicationConstant.POST_CHANGE_STATE_EVENT, cmsService, node);
        if (activityService.isAcceptedNode(node)) {
          listenerService.broadcast(ActivityCommonService.STATE_CHANGED_ACTIVITY, node, newState);
        }
      }
    }
    listenerService.broadcast(AuthoringPublicationConstant.POST_UPDATE_STATE_EVENT, null, node);
  }

  /**
   * Gets the revision data.
   * 
   * @param node
   *          the node
   * @return the revision data
   * @throws Exception
   *           the exception
   */
  private Map<String, VersionData> getRevisionData(Node node) throws Exception {
    Map<String, VersionData> map = new HashMap<String, VersionData>();
    try {
      for (Value v : node.getProperty(AuthoringPublicationConstant.REVISION_DATA_PROP).getValues()) {
        VersionData versionData = VersionData.toVersionData(v.getString());
        map.put(versionData.getUUID(), versionData);
      }
    } catch (Exception e) {
      return map;
    }
    return map;
  }

  private String getCurrenUserName(Node node) throws RepositoryException {
    // Get current user
    String userId = "";
    try {
      userId = ConversationState.getCurrent().getIdentity().getUserId();
    } catch (Exception e) {
      try {
        userId = Util.getPortalRequestContext().getRemoteUser();
      } catch (Exception ex) {
        userId = node.getSession().getUserID();
      }
    }
    return userId;
  }

  private void deleteVersions(Node node) throws Exception {
    // Delete version History
    List<Version> toDeleteVersions = new ArrayList<Version>();
    VersionHistory versionHistory = node.getVersionHistory();
    if (versionHistory != null) {
      VersionIterator versionIterator = versionHistory.getAllVersions();
      while (versionIterator.hasNext()) {
        Version version = versionIterator.nextVersion();
        if (version != null && !version.getPath().equals(node.getPath()) && !version.getName().equals("jcr:rootVersion")) {
          toDeleteVersions.add(0, version);
        }
      }
    }

    node.removeMixin("mix:versionable");
    node.save();
    node.refresh(false);

    for (Version version : toDeleteVersions) {
      try {
        // Delete version declaration from root node
        versionHistory.removeVersion(version.getName());
      } catch (Exception e) {
        if (LOG.isDebugEnabled()) {
          LOG.warn("Can't delete version:" + version.getName(), e);
        }
      }
    }

    // Add mixin mix:versionable
    node.addMixin(Utils.MIX_VERSIONABLE);

    node.save();
    node.refresh(false);
  }

  public String[] getPossibleStates() {
    return new String[]
      { PublicationDefaultStates.ENROLLED, PublicationDefaultStates.DRAFT, PublicationDefaultStates.PENDING, PublicationDefaultStates.PUBLISHED, PublicationDefaultStates.OBSOLETE,
          PublicationDefaultStates.ARCHIVED };
  }

  public String getLifecycleName() {
    return AuthoringPublicationConstant.LIFECYCLE_NAME;
  }

  public String getLifecycleType() {
    return AuthoringPublicationConstant.PUBLICATION_LIFECYCLE_TYPE;
  }

  public UIForm getStateUI(Node node, UIComponent component) throws Exception {
    UIPublicationContainer publicationContainer = component.createUIComponent(UIPublicationContainer.class, null, null);
    publicationContainer.initContainer(node);
    return publicationContainer;
  }

  public void addMixin(Node node) throws Exception {
    node.addMixin(AuthoringPublicationConstant.PUBLICATION_LIFECYCLE_TYPE);
    String nodetypes = System.getProperty("wcm.nodetypes.ignoreversion");
    if (nodetypes == null || nodetypes.length() == 0)
      nodetypes = "exo:webContent";
    if (!Utils.NT_FILE.equals(node.getPrimaryNodeType().getName()) || Utils.isMakeVersionable(node, nodetypes.split(","))) {
      if (!node.isNodeType(AuthoringPublicationConstant.MIX_VERSIONABLE)) {
        node.addMixin(AuthoringPublicationConstant.MIX_VERSIONABLE);
      }
    }
  }

  public boolean canAddMixin(Node node) throws Exception {
    return node.canAddMixin(AuthoringPublicationConstant.PUBLICATION_LIFECYCLE_TYPE);
  }

  /**
   * Adds the log.
   * 
   * @param node
   *          the node
   * @param versionLog
   *          the version log
   * @throws Exception
   *           the exception
   */
  private void addLog(Node node, VersionLog versionLog) throws Exception {
    // Delete old LOG and add only last one
    node.setProperty(AuthoringPublicationConstant.HISTORY, new String[]
      { versionLog.toString() });
  }

  /**
   * Adds the revision data.
   * 
   * @param node
   *          the node
   * @param revisionsMap
   *          the list
   * @throws Exception
   *           the exception
   */
  private void addRevisionData(Node node, String uuid, Map<String, VersionData> revisionsMap, String userId, String newState) throws Exception {
    VersionData versionData = revisionsMap.get(uuid);
    if (versionData != null) {
      versionData.setAuthor(userId);
      versionData.setState(newState);
    } else {
      versionData = new VersionData(uuid, newState, userId);
    }
    revisionsMap.put(uuid, versionData);
    addRevisionData(node, revisionsMap);
  }

  /**
   * Adds the revision data.
   * 
   * @param node
   *          the node
   * @param revisionsMap
   *          the list
   * @throws Exception
   *           the exception
   */
  private void addRevisionData(Node node, Map<String, VersionData> revisionsMap) throws Exception {
    List<Value> valueList = new ArrayList<Value>();
    ValueFactory factory = node.getSession().getValueFactory();

    for (VersionData tmpVersionData : revisionsMap.values()) {
      valueList.add(factory.createValue(tmpVersionData.toStringValue()));
    }
    node.setProperty(AuthoringPublicationConstant.REVISION_DATA_PROP, valueList.toArray(new Value[] {}));
  }

  private String getAuthoringPublicationConstant(String newState) {
    if (PublicationDefaultStates.APPROVED.equals(newState)) {
      return AuthoringPublicationConstant.CHANGE_TO_APPROVED;
    } else if (PublicationDefaultStates.ARCHIVED.equals(newState)) {
      return AuthoringPublicationConstant.CHANGE_TO_ARCHIVED;
    } else if (PublicationDefaultStates.DRAFT.equals(newState)) {
      return AuthoringPublicationConstant.CHANGE_TO_DRAFT;
    } else if (PublicationDefaultStates.ENROLLED.equals(newState)) {
      return AuthoringPublicationConstant.ENROLLED_TO_LIFECYCLE;
    } else if (PublicationDefaultStates.OBSOLETE.equals(newState)) {
      return AuthoringPublicationConstant.CHANGE_TO_OBSOLETED;
    } else if (PublicationDefaultStates.PENDING.equals(newState)) {
      return AuthoringPublicationConstant.CHANGE_TO_PENDING;
    } else if (PublicationDefaultStates.PUBLISHED.equals(newState)) {
      return AuthoringPublicationConstant.CHANGE_TO_LIVE;
    } else if (PublicationDefaultStates.STAGED.equals(newState)) {
      return AuthoringPublicationConstant.CHANGE_TO_STAGED;
    } else if (PublicationDefaultStates.UNPUBLISHED.equals(newState)) {
      return AuthoringPublicationConstant.CHANGE_TO_UNPUBLISHED;
    } else {
      return null;
    }
  }

  /**
   * In this publication process, we put the content in Draft state when editing
   * it.
   */
  public void updateLifecyleOnChangeContent(Node node, String remoteUser, String newState) throws Exception {
    String state = node.getProperty(AuthoringPublicationConstant.CURRENT_STATE).getString();
    if (newState == null) {
      Lifecycle lifecycle = getPublicationManagerImpl().getLifecycle(node.getProperty(PUBLICATION_LIFECYCLE_PROP).getString());
      List<State> states = lifecycle.getStates();
      if (states != null && states.size() > 0) {
        newState = states.get(0).getState();
      }
    }
    if (state.equals(newState)) {
      return;
    }
    changeState(node, newState, new HashMap<String, String>());
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.exoplatform.services.ecm.publication.PublicationPlugin#getNodeView(
   * javax.jcr.Node, java.util.Map)
   */
  public Node getNodeView(Node node, Map<String, Object> context) throws Exception {

    // don't display content if state is enrolled or unpublished
    String currentState = getWcmPublicationService().getContentState(node);
    if (PublicationDefaultStates.ENROLLED.equals(currentState) || PublicationDefaultStates.UNPUBLISHED.equals(currentState))
      return null;

    // if current mode is edit mode
    if (context == null || PublicationDefaultStates.UNPUBLISHED.equals(currentState) || WCMComposer.MODE_EDIT.equals(context.get(WCMComposer.FILTER_MODE))
        || PortletMode.EDIT.toString().equals(context.get(WCMComposer.PORTLET_MODE)))
      return node;

    // if current mode is live mode
    Node liveNode = getLiveRevision(node);
    if (liveNode != null) {
      if (liveNode.hasNode("jcr:frozenNode")) {
        return liveNode.getNode("jcr:frozenNode");
      }
      return liveNode;
    }
    return null;
  }

  @Override
  /**
   * In this publication process, we put the content in Draft state when editing it.
   */
  public void updateLifecyleOnChangeContent(Node node, String remoteUser) throws Exception {
    updateLifecyleOnChangeContent(node, remoteUser, PublicationDefaultStates.DRAFT);
  }

  @Override
  public List<String> getListUserNavigationUri(Page page, String remoteUser) throws Exception {
    UserPortalConfigService userPortalConfigService = WCMCoreUtils.getService(UserPortalConfigService.class);

    List<String> listPageNavigationUri = new ArrayList<String>();
    List<String> listPortalName = userPortalConfigService.getAllPortalNames();
    for (String portalName : listPortalName) {
      UserPortalConfig userPortalCfg = userPortalConfigService.getUserPortalConfig(portalName, remoteUser, PortalRequestContext.USER_PORTAL_CONTEXT);
      UserPortal userPortal = userPortalCfg.getUserPortal();

      // get nodes
      List<UserNavigation> navigationList = userPortal.getNavigations();
      for (UserNavigation nav : navigationList) {
        UserNode root = userPortal.getNode(nav, Scope.ALL, null, null);
        List<UserNode> userNodeList = PublicationUtil.findUserNodeByPageId(root, page.getPageId());
        for (UserNode node : userNodeList) {
          listPageNavigationUri.add(PublicationUtil.setMixedNavigationUri(portalName, node.getURI()));
        }
      }
    }
    return listPageNavigationUri;
  }

  @Override
  public byte[] getStateImage(Node node, Locale locale) throws IOException, FileNotFoundException, Exception {
    return null;
  }

  @Override
  public String getUserInfo(Node node, Locale locale) throws Exception {
    return null;
  }

  @Override
  public String getLocalizedAndSubstituteMessage(Locale locale, String key, String[] values) throws Exception {
    ResourceBundle resourceBundle = resourceBundleService.getResourceBundle(AuthoringPublicationConstant.LOCALIZATION, locale, this.getClass().getClassLoader());

    String result = "";
    try {
      result = resourceBundle.getString(key);
    } catch (MissingResourceException e) {
      result = key;
    }
    if (values != null && values.length > 0) {
      return String.format(result, (Object[]) values);
    }
    return result;
  }

  public PublicationManagerImpl getPublicationManagerImpl() {
    if (publicationManagerImpl == null) {
      publicationManagerImpl = WCMCoreUtils.getService(PublicationManagerImpl.class);
    }
    return publicationManagerImpl;
  }

  public WCMPublicationService getWcmPublicationService() {
    if (wcmPublicationService == null) {
      wcmPublicationService = WCMCoreUtils.getService(WCMPublicationService.class);
    }
    return wcmPublicationService;
  }

  /**
   * Gets the live revision.
   * 
   * @param node
   *          the node
   * @return the live revision
   */
  private Node getLiveRevision(Node node) {
    try {
      // Get current node if its state is "published"
      String currentState = node.hasProperty(AuthoringPublicationConstant.CURRENT_STATE) ? node.getProperty(AuthoringPublicationConstant.CURRENT_STATE).getString() : null;
      if (PublicationDefaultStates.PUBLISHED.equals(currentState)) {
        return node;
      }

      // Get live revision
      String nodeVersionUUID = node.hasProperty(AuthoringPublicationConstant.LIVE_REVISION_PROP) ? node.getProperty(AuthoringPublicationConstant.LIVE_REVISION_PROP).getString() : null;
      if (StringUtils.isEmpty(nodeVersionUUID)) {
        return null;
      } else {
        return node.getVersionHistory().getSession().getNodeByUUID(nodeVersionUUID);
      }
    } catch (Exception e) {
      return null;
    }
  }
}
