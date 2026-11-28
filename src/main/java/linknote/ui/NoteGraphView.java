package linknote.ui;

import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import linknote.links.LinkIndex;
import linknote.model.Note;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public final class NoteGraphView extends BorderPane {
    private static final int MAX_NOTES = 100;
    private final Label summary = new Label();
    private final GraphCanvas canvas = new GraphCanvas();
    private final ScrollPane scroll = new ScrollPane(canvas);
    private final Label notice = new Label();
    private final StackPane noticePane = new StackPane(notice);
    private final Consumer<Note> openNote;

    public NoteGraphView(Consumer<Note> openNote) {
        this.openNote = openNote;
        setId("note-graph");
        getStyleClass().addAll("workspace", "graph-view");
        var heading = new Label("笔记图谱");
        heading.getStyleClass().add("section-title");
        summary.setId("graph-summary");
        summary.getStyleClass().add("secondary");
        var header = new HBox(14, heading, summary);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("graph-header");
        setTop(header);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setPannable(true);
        scroll.getStyleClass().add("graph-scroll");
        notice.getStyleClass().add("secondary");
        noticePane.getStyleClass().add("graph-surface");
    }

    public void showGraph(List<Note> notes, LinkIndex index) {
        if (notes.size() > MAX_NOTES) {
            summary.setText(notes.size() + " 篇笔记");
            notice.setText("图谱最多显示 " + MAX_NOTES + " 篇笔记。当前目录有 " + notes.size() + " 篇。");
            canvas.setGraph(Map.of(), Set.of());
            setCenter(noticePane);
            return;
        }

        Set<LinkIndex.Edge> edges = Set.copyOf(index.edges().stream()
                .filter(edge -> !edge.sourceId().equals(edge.targetId())).toList());
        Map<UUID, Button> nodes = new LinkedHashMap<>();
        for (Note note : notes) {
            var button = new Button(note.title());
            button.setManaged(false);
            button.getStyleClass().add("graph-node");
            button.setTooltip(new Tooltip(note.title() + "\n" + note.path().getFileName()));
            button.setOnAction(event -> openNote.accept(note));
            nodes.put(note.id(), button);
        }
        summary.setText(nodes.size() + " 篇笔记 · " + edges.size() + " 条关联");
        canvas.setGraph(nodes, edges);
        setCenter(scroll);
    }

    private static final class GraphCanvas extends Pane {
        private static final double NODE_WIDTH = 140;
        private static final double NODE_HEIGHT = 44;
        private static final double RING_SPACING = 164;
        private final Canvas arrows = new Canvas();
        private Map<UUID, Button> nodes = Map.of();
        private Set<LinkIndex.Edge> edges = Set.of();

        private GraphCanvas() {
            getStyleClass().add("graph-surface");
            arrows.setManaged(false);
            arrows.setMouseTransparent(true);
        }

        private void setGraph(Map<UUID, Button> nodes, Set<LinkIndex.Edge> edges) {
            this.nodes = nodes;
            this.edges = edges;
            getChildren().setAll(arrows);
            getChildren().addAll(nodes.values());

            int rings = 0;
            for (int capacity = 0; capacity < nodes.size(); ) {
                rings++;
                capacity += 6 * rings;
            }
            double diameter = nodes.size() > 2 ? 2 * rings * RING_SPACING : 0;
            setMinSize(Math.max(480, diameter + NODE_WIDTH + 48), Math.max(300, diameter + NODE_HEIGHT + 48));
            requestLayout();
        }

        @Override
        protected void layoutChildren() {
            arrows.setWidth(getWidth());
            arrows.setHeight(getHeight());
            GraphicsContext graphics = arrows.getGraphicsContext2D();
            graphics.clearRect(0, 0, getWidth(), getHeight());
            graphics.setStroke(Color.web("#648277"));
            graphics.setFill(Color.web("#648277"));
            graphics.setLineWidth(1.8);

            Map<UUID, Point2D> centers = new HashMap<>();
            Point2D origin = new Point2D(getWidth() / 2, getHeight() / 2);
            int remaining = nodes.size();
            int ring = 1;
            var entries = nodes.entrySet().iterator();
            while (remaining > 0) {
                int points = Math.min(remaining, 6 * ring);
                for (int position = 0; position < points; position++) {
                    var entry = entries.next();
                    Point2D center = centerFor(origin, ring, points, position);
                    centers.put(entry.getKey(), center);
                    entry.getValue().resizeRelocate(center.getX() - NODE_WIDTH / 2, center.getY() - NODE_HEIGHT / 2,
                            NODE_WIDTH, NODE_HEIGHT);
                }
                remaining -= points;
                ring++;
            }
            for (LinkIndex.Edge edge : edges) {
                Point2D source = centers.get(edge.sourceId());
                Point2D target = centers.get(edge.targetId());
                boolean reciprocal = edges.contains(new LinkIndex.Edge(edge.targetId(), edge.sourceId()));
                drawArrow(graphics, source, target, reciprocal);
            }
        }

        private Point2D centerFor(Point2D origin, int ring, int points, int position) {
            if (nodes.size() == 1) {
                return origin;
            }
            if (nodes.size() == 2) {
                double offset = Math.min(getWidth() / 4, 240);
                return origin.add(position == 0 ? -offset : offset, 0);
            }
            double phase = (ring % 2 == 0) ? Math.PI / points : 0;
            double angle = -Math.PI / 2 + phase + 2 * Math.PI * position / points;
            return origin.add(RING_SPACING * ring * Math.cos(angle), RING_SPACING * ring * Math.sin(angle));
        }

        private static void drawArrow(GraphicsContext graphics, Point2D source, Point2D target, boolean reciprocal) {
            Point2D direction = target.subtract(source).normalize();
            Point2D normal = new Point2D(-direction.getY(), direction.getX());
            Point2D shift = reciprocal ? normal.multiply(5) : Point2D.ZERO;
            double clearance = Math.min((NODE_WIDTH / 2 + 6) / Math.abs(direction.getX()),
                    (NODE_HEIGHT / 2 + 6) / Math.abs(direction.getY()));
            Point2D start = source.add(direction.multiply(clearance)).add(shift);
            Point2D tip = target.subtract(direction.multiply(clearance)).add(shift);
            Point2D base = tip.subtract(direction.multiply(9));
            Point2D left = base.add(normal.multiply(4));
            Point2D right = base.subtract(normal.multiply(4));
            graphics.strokeLine(start.getX(), start.getY(), base.getX(), base.getY());
            graphics.fillPolygon(new double[]{tip.getX(), left.getX(), right.getX()},
                    new double[]{tip.getY(), left.getY(), right.getY()}, 3);
        }
    }
}
