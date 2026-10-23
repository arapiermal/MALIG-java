module com.erimali.multiAgentCore {
    requires javafx.controls;
    requires javafx.fxml;
    requires com.google.genai;
    requires java.net.http;
    requires com.google.gson;


    exports app.core.agent;
    exports app.core.capability;
    exports app.core.environment;
    exports app.core.memory;
    exports app.core.message;
    exports app.core.planning;
    exports app.core.runtime;
    exports app.llm;
    exports app.llm.provider;
    exports app.scenario.synthetic;
    exports com.erimali.multiAgentFX;
    exports com.erimali.multiAgentFX.agenteditor;
    exports com.erimali.multiAgentFX.bridge;
    exports com.erimali.multiAgentFX.model;
    exports com.erimali.multiAgentFX.panel;
    exports com.erimali.multiAgentFX.stage;
    opens com.erimali.multiAgentFX to javafx.fxml;
}
