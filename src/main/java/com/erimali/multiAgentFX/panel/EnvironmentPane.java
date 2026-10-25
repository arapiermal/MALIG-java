package com.erimali.multiAgentFX.panel;

import app.core.environment.*;
import app.core.environment.EnvironmentManagement.*;
import app.core.runtime.*;
import com.erimali.multiAgentFX.bridge.RuntimeFacade;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Host-only typed observation editor. All inspection/mutation is serialized by the facade/runtime. */
public final class EnvironmentPane extends VBox {
    private final RuntimeFacade facade;
    private final TableView<Fact> facts = new TableView<>();
    private final ListView<WorldEntity> entities = new ListView<>();
    private final TextArea factDetails = readOnly(), entityDetails = readOnly(), string = new TextArea();
    private final TextField name = new TextField(), number = new TextField();
    private final ComboBox<FactType> type = new ComboBox<>();
    private final CheckBox bool = new CheckBox("True (unchecked = false)");
    private final Button add = new Button("Add fact"), edit = new Button("Edit selected"), delete = new Button("Delete custom"), save = new Button("Create fact");
    private final Label availability = label("Inspecting..."), feedback = label("");
    private final TitledPane editor = new TitledPane();
    private final AtomicBoolean queued = new AtomicBoolean(), dirty = new AtomicBoolean();
    private AgentRuntime.EnvironmentInspection inspection;
    private Fact editing;
    private boolean creating = true, saving;
    private String selectAfterRefresh;

    public EnvironmentPane(RuntimeFacade facade) {
        this.facade = facade; setSpacing(7); setPadding(new javafx.geometry.Insets(6));
        facts.setId("environment-facts"); entities.setId("environment-entities");
        factDetails.setId("environment-fact-details"); entityDetails.setId("environment-entity-details");
        name.setId("environment-fact-name"); type.setId("environment-fact-type"); number.setId("environment-fact-number");
        string.setId("environment-fact-string"); bool.setId("environment-fact-boolean");
        add.setId("environment-add"); edit.setId("environment-edit"); delete.setId("environment-delete"); save.setId("environment-save");
        availability.setId("environment-status"); feedback.setId("environment-feedback"); editor.setId("environment-editor");
        factDetails.setPrefRowCount(3); entityDetails.setPrefRowCount(7); string.setPrefRowCount(3); string.setWrapText(true);
        column("Fact", f -> f.name(), 110); column("Type", f -> f.type().name(), 65);
        column("Current value", f -> shortValue(f.value()), 100); column("Host access", f -> f.deletable()?"Edit/delete":f.editable()?"Edit":"Read-only", 85);
        facts.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY); facts.setPlaceholder(label("No facts in this environment"));
        facts.getSelectionModel().selectedItemProperty().addListener((o,a,b) -> { showFact(); updateButtons(); });
        type.getItems().setAll(FactType.INTEGER,FactType.NUMBER,FactType.BOOLEAN,FactType.STRING); type.setValue(FactType.NUMBER);
        type.setMaxWidth(Double.MAX_VALUE); type.setOnAction(e -> showValueEditor());
        number.setPromptText("Finite number"); name.setPromptText("e.g. demand (letters/digits/underscores)");
        add.setOnAction(e -> {
            creating=true;editing=null;name.setDisable(false);type.setDisable(false);name.clear();number.clear();string.clear();bool.setSelected(false);
            type.setValue(FactType.NUMBER);save.setText("Create fact");editor.setText("New shared observation");editor.setExpanded(true);showValueEditor();updateButtons();
        });
        edit.setOnAction(e -> {
            var selected=facts.getSelectionModel().getSelectedItem();if(selected==null || !selected.editable())return;
            creating=false;editing=selected;name.setText(selected.name());name.setDisable(true);type.setValue(selected.type());type.setDisable(true);
            if(selected.type()==FactType.BOOLEAN)bool.setSelected((Boolean)selected.value());
            else if(selected.type()==FactType.STRING)string.setText((String)selected.value());
            else number.setText(selected.type()==FactType.INTEGER ? Long.toString(((Number)selected.value()).longValue()) : selected.value().toString());
            save.setText("Save value");editor.setText("Edit "+selected.name());editor.setExpanded(true);showValueEditor();updateButtons();
        });
        save.setOnAction(e -> {
            try {
                if(!creating && editing==null)throw new IllegalArgumentException("Select Edit to reload the fact");
                FactType selected=type.getValue();if(selected==null)throw new IllegalArgumentException("Choose a type");
                Object value=selected==FactType.BOOLEAN?bool.isSelected():selected.parse(selected==FactType.STRING?string.getText():number.getText());
                change(new Change(creating?Operation.CREATE:Operation.UPDATE,name.getText(),selected,value,creating?null:editing.value()));
            } catch(RuntimeException ex) { feedback.setText("Invalid value: "+ex.getMessage()); }
        });
        delete.setOnAction(e -> {var selected=facts.getSelectionModel().getSelectedItem();if(selected!=null && selected.deletable())change(new Change(Operation.DELETE,selected.name(),selected.type(),null,selected.value()));});
        Button close=new Button("Close editor");close.setOnAction(e -> editor.setExpanded(false));
        VBox form=new VBox(6,label("Fact name"),name,label("Type (fixed after creation)"),type,label("Value"),number,bool,string,new FlowPane(6,6,save,close));
        editor.setContent(form);editor.setText("Fact editor");editor.setAnimated(false);editor.setExpanded(false);showValueEditor();
        VBox factPane=new VBox(6,new FlowPane(6,6,add,edit,delete),facts,factDetails,editor);VBox.setVgrow(facts,Priority.ALWAYS);
        entities.setCellFactory(list -> new ListCell<>() {protected void updateItem(WorldEntity e,boolean empty){super.updateItem(e,empty);setText(empty||e==null?null:e.name()+" ("+e.type()+")");}});
        entities.getSelectionModel().selectedItemProperty().addListener((o,a,b) -> showEntity());
        VBox entityPane=new VBox(6,label("Entities and regional attributes are read-only here."),entities,entityDetails);VBox.setVgrow(entities,Priority.ALWAYS);
        TabPane tabs=new TabPane(new Tab("Facts",factPane),new Tab("Entities",entityPane));tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);tabs.setId("environment-tabs");
        Button refresh=new Button("Refresh");refresh.setId("environment-refresh");refresh.setOnAction(e -> requestRefresh());
        getChildren().addAll(label(facade.scenarioName()),availability,feedback,refresh,tabs);VBox.setVgrow(tabs,Priority.ALWAYS);
        facade.onEvent(e -> {if(Set.of(ExecutionEventType.HOST_ENVIRONMENT_CHANGE,ExecutionEventType.ACTION_COMPLETED,ExecutionEventType.ENVIRONMENT_ACTION,
                ExecutionEventType.ENVIRONMENT_TICK,ExecutionEventType.RUNTIME_STARTED,ExecutionEventType.RUNTIME_STOPPED,ExecutionEventType.GOAL_SUBMITTED,
                ExecutionEventType.GOAL_COMPLETED,ExecutionEventType.GOAL_CANCELLED,ExecutionEventType.AGENT_REMOVED,ExecutionEventType.REQUEST_SENT,
                ExecutionEventType.REQUEST_COMPLETED,ExecutionEventType.CONVERSATION_CANCELLED,ExecutionEventType.COLLABORATION_CHANGED).contains(e.type()))requestRefresh();});
        updateButtons();requestRefresh();
    }
    private static Label label(String text){Label l=new Label(text);l.setWrapText(true);return l;}
    private static TextArea readOnly(){TextArea a=new TextArea();a.setEditable(false);a.setWrapText(true);return a;}
    private static String shortValue(Object value){String s=String.valueOf(value).replace('\n',' ');return s.substring(0,Math.min(100,s.length()));}
    private void column(String title,java.util.function.Function<Fact,String> value,int width){TableColumn<Fact,String> c=new TableColumn<>(title);c.setCellValueFactory(v -> new ReadOnlyStringWrapper(value.apply(v.getValue())));c.setPrefWidth(width);facts.getColumns().add(c);}
    private void showValueEditor(){FactType selected=type.getValue();for(var node:List.of(number,bool,string)){boolean show=node==bool?selected==FactType.BOOLEAN:node==string?selected==FactType.STRING:selected==FactType.NUMBER||selected==FactType.INTEGER;node.setVisible(show);node.setManaged(show);}number.setPromptText(selected==FactType.INTEGER?"64-bit integer (resourceLevel: 0-100)":"Finite number");}
    private void change(Change change){
        saving=true;updateButtons();feedback.setText("Queued host change...");
        try { facade.changeEnvironmentFact(change).whenComplete((v,error) -> Platform.runLater(() -> {
            saving=false;
            if(error==null){feedback.setText("Host "+change.operation()+": "+change.name()+". Authoritative observations updated.");editor.setExpanded(false);selectAfterRefresh=change.name();}
            else {Throwable cause=error.getCause()==null?error:error.getCause();feedback.setText("Rejected: "+cause.getMessage());}
            updateButtons();requestRefresh();
        })); } catch(java.util.concurrent.RejectedExecutionException closed) { inspectorClosed(); }
    }
    private void inspectorClosed(){inspection=null;saving=false;queued.set(false);dirty.set(false);availability.setText("Environment inspector closed");updateButtons();}
    private void updateButtons(){boolean allowed=!saving&&inspection!=null&&inspection.configurable()&&inspection.restriction().isEmpty();var selected=facts.getSelectionModel().getSelectedItem();
        add.setDisable(!allowed);edit.setDisable(!allowed||selected==null||!selected.editable());delete.setDisable(!allowed||selected==null||!selected.deletable());save.setDisable(!allowed);}
    private void showFact(){var f=facts.getSelectionModel().getSelectedItem();String value=f==null?"Select a fact to inspect its full value and host permissions.":f.name()+" | "+f.type()+"\n"+f.description()+"\nCurrent value:\n"+f.value();if(!factDetails.getText().equals(value))factDetails.setText(value);}
    private void showEntity(){var e=entities.getSelectionModel().getSelectedItem();String value=e==null?"Select an entity.":e.name()+" ["+e.id()+"]\nType: "+e.type()+"\n"+e.attributes().entrySet().stream().map(v -> v.getKey()+" = "+v.getValue()).collect(java.util.stream.Collectors.joining("\n"));if(!entityDetails.getText().equals(value))entityDetails.setText(value);}
    private void requestRefresh(){dirty.set(true);if(queued.compareAndSet(false,true))Platform.runLater(this::refresh);}
    private void refresh(){
        dirty.set(false);
        java.util.concurrent.CompletableFuture<AgentRuntime.EnvironmentInspection> future;
        try { future=facade.inspectEnvironment(); } catch(java.util.concurrent.RejectedExecutionException closed) { inspectorClosed();return; }
        future.whenComplete((next,error) -> Platform.runLater(() -> {
            if(error!=null)availability.setText("Inspection failed: "+(error.getCause()==null?error:error.getCause()).getMessage());
            else {
                inspection=next;String selected=selectAfterRefresh!=null?selectAfterRefresh:facts.getSelectionModel().getSelectedItem()==null?null:facts.getSelectionModel().getSelectedItem().name();selectAfterRefresh=null;
                sync(facts.getItems(),next.facts());next.facts().stream().filter(f -> f.name().equals(selected)).findFirst().ifPresent(facts.getSelectionModel()::select);
                UUID entity=entities.getSelectionModel().getSelectedItem()==null?null:entities.getSelectionModel().getSelectedItem().id();
                sync(entities.getItems(),next.snapshot().entities());next.snapshot().entities().stream().filter(e -> e.id().equals(entity)).findFirst().ifPresent(entities.getSelectionModel()::select);
                availability.setText(next.facts().size()+" facts | "+next.snapshot().entities().size()+" entities\n"+(next.restriction().isEmpty()?"Host edits allowed while idle. Agent tools still require grants.":next.restriction()));
                showFact();showEntity();updateButtons();
            }
            queued.set(false);if(dirty.get())requestRefresh();
        }));
    }
    private static <T> void sync(javafx.collections.ObservableList<T> existing,List<T> next){for(int i=0;i<next.size();i++){if(i>=existing.size())existing.add(next.get(i));else if(!Objects.equals(existing.get(i),next.get(i)))existing.set(i,next.get(i));}if(existing.size()>next.size())existing.remove(next.size(),existing.size());}
}
