package org.erlide.cover.core;

import java.io.File;

import org.erlide.cover.api.IConfiguration;
import org.erlide.cover.views.model.FunctionStats;
import org.erlide.cover.views.model.ICoverageObject;
import org.erlide.cover.views.model.LineResult;
import org.erlide.cover.views.model.ModuleSet;
import org.erlide.cover.views.model.ModuleStats;
import org.erlide.cover.views.model.ObjectType;
import org.erlide.cover.views.model.StatsTreeModel;
import org.erlide.cover.views.model.StatsTreeObject;
import org.erlide.engine.ErlangEngine;
import org.erlide.runtime.events.ErlEvent;
import org.erlide.runtime.events.ErlangEventHandler;
import org.erlide.util.ErlLogger;

import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangList;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangTuple;
import com.google.common.eventbus.Subscribe;

/**
 * Handler for coverage events
 *
 * @author Aleksandra Lipiec <aleksandra.lipiec@erlang.solutions.com>
 *
 */
public class CoverEventHandler extends ErlangEventHandler {

    private static final String EVENT_NAME = "cover_event";
    private static final String COVER_FIN = "cover_fin";
    private static final String COVER_ERROR = "cover_error";
    private static final String COVER_RES = "module_res";

    private final Logger log; // log
    private final CoverBackend coverBackend; // cover backend

    public CoverEventHandler(final CoverBackend coverBackend) {
        super(CoverEventHandler.EVENT_NAME);
        this.coverBackend = coverBackend;
        log = Activator.getDefault();
    }

    @Subscribe
    public void handleEvent(final ErlEvent event) {
        if (!event.getTopic().equals(getTopic())) {
            return;
        }
        OtpErlangTuple tuple = null;

        final OtpErlangObject data = event.getEvent();
        if (gotResults(data)) {
            for (final ICoverObserver obs : coverBackend.getListeners()) {
                obs.eventOccured(new CoverEvent(CoverStatus.UPDATE));
            }
        } else if ((tuple = getErrorReason(data)) != null) {
            final String place = tuple.elementAt(1).toString();
            final String type = tuple.elementAt(2).toString();
            final String info = tuple.elementAt(3).toString();

            for (final ICoverObserver obs : coverBackend.getListeners()) {
                obs.eventOccured(new CoverEvent(CoverStatus.ERROR,
                        String.format("Error at %s while %s: %s\n", place, type, info)));
            }
        } else if (data.toString().equals(CoverEventHandler.COVER_FIN)
                && coverBackend.getAnnotationMaker() != null) {
            coverBackend.getAnnotationMaker().addAnnotations();
        }

    }

    /**
     * When coverage results came
     *
     * @param msg
     * @return
     */
    private boolean gotResults(final OtpErlangObject msg) {
        if (msg instanceof OtpErlangTuple) {
            final OtpErlangTuple resTuple = (OtpErlangTuple) msg;
            if (resTuple.elementAt(0) instanceof OtpErlangAtom
                    && ((OtpErlangAtom) resTuple.elementAt(0)).atomValue()
                            .equals(CoverEventHandler.COVER_RES)) {

                final String moduleName = resTuple.elementAt(1).toString();

                String htmlPath = resTuple.elementAt(2).toString();
                htmlPath = htmlPath.substring(1, htmlPath.length() - 1);
                final int allLines = Integer.parseInt(resTuple.elementAt(3).toString());
                final int coveredLines = Integer
                        .parseInt(resTuple.elementAt(4).toString());
                final double percent = Double
                        .parseDouble(resTuple.elementAt(5).toString());

                log.info(String.format("Module %s %s %d %d %f", moduleName, htmlPath,
                        allLines, coveredLines, percent));

                final ModuleStats moduleStats = new ModuleStats();

                moduleStats.setLabel(moduleName);
                moduleStats.setHtmlPath(htmlPath);
                moduleStats.setLiniesCount(allLines);
                moduleStats.setCoverCount(coveredLines);

                // calculate md5

                try {
                    final File file = new File(ErlangEngine.getInstance().getModel()
                            .findModule(moduleName).getFilePath());
                    moduleStats.setMd5(MD5Checksum.getMD5(file));
                } catch (final Exception e) {
                    ErlLogger.error(e);
                }

                //

                prepLineResults((OtpErlangList) resTuple.elementAt(6), moduleStats);

                prepFuncResults((OtpErlangList) resTuple.elementAt(7), moduleStats);

                addModuleToTree(moduleStats);

                ModuleSet.add(moduleStats);

                return true;
            }
            return false;
        }
        return false;
    }

    // adds module to the statistics tree
    private void addModuleToTree(final ModuleStats moduleStats) {

        ICoverageObject root = StatsTreeModel.getInstance().getRoot();

        final IConfiguration config = CoveragePerformer.getPerformer().getConfig();

        final String ppath = ErlangEngine.getInstance().getModelUtilService()
                .getProject(config.getProject()).getWorkspaceProject().getLocation()
                .toString();
        String mpath = config.getModule(moduleStats.getLabel()).getFilePath();
        mpath = mpath.substring(ppath.length());
        log.info(ppath);
        log.info(mpath);

        final String[] parts = mpath.split("/");

        root.setLiniesCount(root.getLinesCount() + moduleStats.getLinesCount());
        root.setCoverCount(root.getCoverCount() + moduleStats.getCoverCount());

        for (int i = 1; i < parts.length - 1; i++) {

            ICoverageObject tmp = root.findChild(parts[i]);
            if (tmp == null) {
                tmp = new StatsTreeObject(ObjectType.FOLDER);
                tmp.setLabel(parts[i]);
            }
            tmp.setLiniesCount(tmp.getLinesCount() + moduleStats.getLinesCount());
            tmp.setCoverCount(tmp.getCoverCount() + moduleStats.getCoverCount());
            root.addChild(parts[i], tmp);
            root = tmp;
        }

        root.addChild(moduleStats.getLabel(), moduleStats);

    }

    private void prepFuncResults(final OtpErlangList funcList, final ModuleStats stats) {

        for (OtpErlangObject aFuncList : funcList) {
            final OtpErlangTuple res = (OtpErlangTuple) aFuncList;

            final FunctionStats func = new FunctionStats();

            final String name = res.elementAt(1).toString();
            final int arity = Integer.parseInt(res.elementAt(2).toString());
            final int allLines = Integer.parseInt(res.elementAt(3).toString());
            final int coveredLines = Integer.parseInt(res.elementAt(4).toString());

            func.setLabel(name);
            func.setLiniesCount(allLines);
            func.setCoverCount(coveredLines);
            func.setArity(arity);

            stats.addChild(func.getLabel(), func);
        }

    }

    private void prepLineResults(final OtpErlangList lineList, final ModuleStats stats) {

        for (OtpErlangObject aLineList : lineList) {
            final OtpErlangTuple res = (OtpErlangTuple) aLineList;
            final int num = Integer.parseInt(res.elementAt(1).toString());
            final int calls = Integer.parseInt(res.elementAt(2).toString());
            final LineResult lineRes = new LineResult(num, calls);

            stats.addLine(lineRes);

        }

    }

    private OtpErlangTuple getErrorReason(final OtpErlangObject message) {
        if (message instanceof OtpErlangTuple) {
            final OtpErlangTuple tuple = (OtpErlangTuple) message;
            if (tuple.elementAt(0) instanceof OtpErlangAtom
                    && ((OtpErlangAtom) tuple.elementAt(0)).atomValue()
                            .equals(CoverEventHandler.COVER_ERROR)) {

                return tuple;
            }
        }
        return null;
    }
}
