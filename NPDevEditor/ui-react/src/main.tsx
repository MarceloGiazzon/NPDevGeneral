import React from "react";
import ReactDOM from "react-dom/client";
import { installDefaultApiKeyFetch } from "./api/apiKey";
import App from "./App";
import "./styles.css";

installDefaultApiKeyFetch();

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
