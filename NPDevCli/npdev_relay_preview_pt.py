"""The plain-language preview of a browser-AI answer, in Portuguese (pt-BR).

`npdev_relay_authoring` builds every preview line in English with a stable `code` and the `params` it was filled
from (`lines[]`, `warnings[]`). This module writes the same lines in Portuguese from those codes and params, so
the Manager shows the person what an answer would change in their own language -- the words are chosen here, once,
and the screen only draws them (it never translates a half-built sentence).

`localize_preview(preview, language)` is the one entry point: any language that is not Portuguese gets the
preview back untouched, and a line whose code or params it does not know keeps its English text rather than
being dropped or guessed. Model names, field labels and the AI's own words are data and are never translated.
"""
from __future__ import annotations

import copy

# (singular, plural, gender) of what the CLI counts and names in a preview. Anything not listed keeps the
# English words the CLI made from the key.
ITEM_PT = {
    "concepts": ("tipo de registro", "tipos de registro", "m"),
    "fields": ("campo", "campos", "m"),
    "flows": ("processo", "processos", "m"),
    "queries": ("busca", "buscas", "f"),
    "procedures": ("ação", "ações", "f"),
    "panels": ("tela", "telas", "f"),
    "guidePages": ("página de guia", "páginas de guia", "f"),
    "autoPanels": ("tela gerada", "telas geradas", "f"),
    "documents": ("documento", "documentos", "m"),
    "roles": ("papel", "papéis", "m"),
    "events": ("evento", "eventos", "m"),
    "webhooks": ("webhook", "webhooks", "m"),
    "aggregates": ("resumo", "resumos", "m"),
    "selectors": ("seletor", "seletores", "m"),
    "sequences": ("sequência de numeração", "sequências de numeração", "f"),
    "seeds": ("conjunto de dados iniciais", "conjuntos de dados iniciais", "m"),
    "domainTypes": ("tipo de dado próprio", "tipos de dado próprios", "m"),
    "bindings": ("conexão", "conexões", "f"),
    "capabilities": ("capacidade", "capacidades", "f"),
    "conversions": ("conversão", "conversões", "f"),
    "properties": ("configuração", "configurações", "f"),
    "propertyScopes": ("nível de configuração", "níveis de configuração", "m"),
    "orchestrationRules": ("regra de automação", "regras de automação", "f"),
    "orchestrations": ("automação", "automações", "f"),
    "ruleProfiles": ("perfil de regras", "perfis de regras", "m"),
    "contexts": ("área", "áreas", "f"),
    "customCapabilities": ("serviço de que precisa", "serviços de que precisa", "m"),
}

# What changed about one field or concept, by the key that changed (mirrors the CLI's `_attr_words`).
ATTR_PT = {
    "type": "o tipo",
    "required": "se é obrigatório",
    "unique": "se precisa ser único",
    "ui": "como aparece",
    "default": "o valor padrão",
    "reference": "para onde aponta",
    "values": "os valores permitidos",
    "enum": "os valores permitidos",
    "length": "o tamanho",
    "min": "o menor valor",
    "max": "o maior valor",
    "invariants": "as regras",
    "access": "as regras de acesso",
    "indexes": "os índices",
    "sensitive": "se é sensível",
}
ATTR_OTHER_PT = "as configurações"

# The words the CLI puts in the "from ... to ..." of an access change.
DEFAULT_PT = "o padrão"
SENSITIVE_PT = {"marked as sensitive": "marcado como sensível", "no longer marked as sensitive": "não mais marcado como sensível"}

# What a pack's version change is, and who an access rule admits (P4.4).
PACK_CHANGE_PT = {"ADDITIVE": "algo novo", "BREAKING": "algo removido ou mudado em que os apps podem se apoiar", "PATCH": "um ajuste pequeno"}
PACK_KIND_PT = {"ADDITIVE": "Novidade", "BREAKING": "Atenção, pode quebrar apps", "PATCH": "Ajuste"}
PACK_WHO_PT = {"anyone": "qualquer pessoa conectada", "nobody": "ninguém",
               "rule": "só as pessoas que uma regra permite (por exemplo, o dono do registro)"}

# Every preview line and warning, by the CLI's own code / family. Same placeholders as the English templates.
TEMPLATES_PT = {
    "CREATE_SUMMARY": "Um app novo com {counts_text}.",
    "CREATE_CONCEPT": "{label}: {count_text}{fields_part}.",
    "CREATE_NOTHING_DELETED": "Nada é apagado: este é um app novo.",
    "ADD_CONCEPT": "Novo tipo de registro: {label}, com {count_text}.",
    "ADD_FIELDS": "{concept}: {field_word} {fields_text}.",
    "CHANGE_FIELD": "{concept}: o campo {field} muda ({what_text}).",
    "CHANGE_CONCEPT": "{concept}: mudanças em {what_text}.",
    "ADD_ITEM": "{new_word} {kind}: {label}.",
    "CHANGE_ITEM": "Mudanças {in_the} {kind} {label}.",
    "CHANGE_SETTINGS": "Mudanças nas configurações do app ({what_text}).",
    "MORE": "...e mais {count_text}.",
    "NO_CHANGES": "A IA devolveu o mesmo app: nada mudaria.",
    "AI_ASSUMPTION": "A IA presumiu: {text}",
    "REMOVAL:concept": "Isto remove o tipo de registro {label} e todos os dados guardados nele.",
    "REMOVAL:field": "Isto remove o campo {label} de {concept} e os dados guardados nele.",
    "RENAME:concept": "{from_label} passa a se chamar {to_label}. Os dados são mantidos.",
    "RENAME:field": "Em {concept}, o campo {from_label} passa a se chamar {to_label}. Os dados são mantidos.",
    "SECURITY:access.read": "Quem pode ver {concept} muda: de {from_text} para {to_text}.",
    "SECURITY:access.write": "Quem pode alterar {concept} muda: de {from_text} para {to_text}.",
    "SECURITY:sensitive": "O campo {field} de {concept} agora está {to_text}.",
    "SECURITY:invariant": "As regras que mantêm os dados de {concept} válidos mudam.",
    "SECURITY:permissionRequirements": "As permissões necessárias para usar {concept} mudam: de {from_text} para {to_text}.",
    "AI_SECURITY_NOTE": "A IA diz sobre acesso: {text}",
    "PACK_SUMMARY": "Um pacote reutilizável \"{pack}\" (versão {version}) com {counts_text}.",
    "PACK_NEW": "Nada é substituído: este é um pacote novo.",
    "PACK_NEW_VERSION": "A versão {old} passa a ser a {new}{change_text}.",
    "PACK_ACCESS": "{label}: visto por {read_text}; alterado por {write_text}.",
    "PACK_LIFECYCLE": "{label} passa por: {states_text}.",
    "PACK_QUERY": "Busca {name}: lê registros de {concept}.",
    "PACK_NEEDS": "Pede ao app que o usar: {names_text}.",
    "PACK_CHANGE": "{kind_text}: {message}",
    "PACK_MORE": "...e mais {count} {what}.",
    "PACK_BREAKING": "Esta versão remove ou muda algo em que os apps que já usam o pacote podem se apoiar: eles vão precisar de uma atualização.",
    "COULD_NOT_EXPRESS": "A IA não conseguiu fazer esta parte: {text}",
}


def is_portuguese(language) -> bool:
    """`Portuguese (Brazil)` (what the Manager sends for pt), `pt`, `pt-BR` ..."""
    text = str(language or "").strip().lower()
    return text.startswith("portuguese") or text.split("-")[0].split("_")[0] == "pt"


def _join(items) -> str:
    items = [str(i) for i in items]
    if len(items) <= 1:
        return "".join(items)
    return ", ".join(items[:-1]) + " e " + items[-1]


def _pl(count: int, key: str) -> str:
    one, many, _gender = ITEM_PT[key]
    return f"{count} {one if count == 1 else many}"


def _attr(keys) -> str:
    seen = []
    for key in keys or []:
        word = ATTR_PT.get(key, ATTR_OTHER_PT)
        if word not in seen:
            seen.append(word)
    return _join(seen)


def _text(value) -> str:
    """A `from`/`to` value of an access change: the CLI's English "the default" and sensitivity words, else as is."""
    if value == "the default":
        return DEFAULT_PT
    return SENSITIVE_PT.get(value, value)


def _item(params):
    """(kind word, gender) for a collection key; a key this module has no words for keeps the CLI's English word."""
    entry = ITEM_PT.get(params.get("collection"))
    if entry:
        return entry[0], entry[2]
    return str(params.get("kind") or ""), "m"


def _line_text(code: str, p: dict):
    """The Portuguese sentence of one preview line, or None when this module cannot write it from `p`."""
    template = TEMPLATES_PT.get(code)
    if template is None:
        return None
    if code == "CREATE_SUMMARY":
        counts = [(key, n) for key, n in p["counts"] if n]
        text = _join([_pl(n, key) for key, n in counts]) if counts else "nada ainda"
        return template.format(counts_text=text)
    if code == "CREATE_CONCEPT":
        shown = list(p["fields_shown"])
        more = int(p.get("fields_more") or 0)
        fields_text = _join(shown) + (f" e mais {more}" if more else "")
        return template.format(label=p["label"], count_text=_pl(int(p["count"]), "fields"),
                               fields_part=f" ({fields_text})" if p["count"] else "")
    if code == "ADD_CONCEPT":
        return template.format(label=p["label"], count_text=_pl(int(p["count"]), "fields"))
    if code == "ADD_FIELDS":
        many = int(p["count"]) != 1
        return template.format(concept=p["concept"], field_word="novos campos" if many else "novo campo",
                               fields_text=_join(p["labels"]))
    if code in ("CHANGE_FIELD", "CHANGE_CONCEPT"):
        return template.format(concept=p["concept"], field=p.get("field", ""), what_text=_attr(p["keys"]))
    if code in ("ADD_ITEM", "CHANGE_ITEM"):
        kind, gender = _item(p)
        return template.format(new_word="Nova" if gender == "f" else "Novo", in_the="na" if gender == "f" else "no",
                               kind=kind, label=p["label"])
    if code == "CHANGE_SETTINGS":
        words = [str(k) for k in p["keys"]]
        return template.format(what_text=_join(_humanize(k) for k in words))
    if code == "MORE":
        return template.format(count_text=str(p["count_text"]))
    if code in ("CREATE_NOTHING_DELETED", "NO_CHANGES", "PACK_NEW"):
        return template
    if code == "PACK_SUMMARY":
        counts = [(key, n) for key, n in p["counts"] if n]
        return template.format(pack=p["pack"], version=p["version"],
                               counts_text=_join([_pl(n, key) for key, n in counts]) if counts else "nada ainda")
    if code == "PACK_NEW_VERSION":
        change = PACK_CHANGE_PT.get(p.get("change"))
        return template.format(old=p["old"], new=p["new"], change_text=f" ({change})" if change else "")
    if code == "PACK_ACCESS":
        return template.format(label=p["label"], read_text=_who_pt(p["read_kind"], p["read_roles"]),
                               write_text=_who_pt(p["write_kind"], p["write_roles"]))
    if code == "PACK_LIFECYCLE":
        return template.format(label=p["label"], states_text=_join(p["states"]))
    if code == "PACK_NEEDS":
        return template.format(names_text=_join(p["names"]))
    if code == "PACK_CHANGE":
        return template.format(kind_text=PACK_KIND_PT.get(p.get("classification"), "Mudança"), message=p["message"])
    if code == "PACK_MORE":
        what = "tipos de registro" if p.get("key") == "concepts" else "mudanças"
        return template.format(count=p["count"], what=what)
    return template.format(**{k: v for k, v in p.items()})


def _who_pt(kind: str, roles) -> str:
    return _join(roles) if kind == "roles" and roles else PACK_WHO_PT.get(kind, kind)


def _humanize(key: str) -> str:
    out = []
    for i, ch in enumerate(key):
        if i and ch.isupper() and (key[i - 1].islower() or key[i - 1].isdigit()):
            out.append(" ")
        out.append(ch)
    return "".join(out).lower()


def _warning_text(warning: dict):
    params = dict(warning.get("params") or {})
    family = params.pop("family", None)
    template = TEMPLATES_PT.get(family or "")
    if template is None:
        return None
    for key in ("from_text", "to_text"):
        if key in params:
            params[key] = _text(params[key])
    try:
        return template.format(**params)
    except (KeyError, IndexError):
        return None


def localize_preview(preview, language):
    """The same preview with its sentences and warning texts in Portuguese; anything else comes back untouched.

    Codes, params, `needsConfirmation` and `dataLoss` are never changed -- Apply keys off the codes, so the words can
    change without touching what a tick means. A line that cannot be written falls back to its English text."""
    if not isinstance(preview, dict) or not is_portuguese(language):
        return preview
    out = copy.deepcopy(preview)
    lines = out.get("lines")
    if isinstance(lines, list) and len(lines) == len(out.get("sentences") or []):
        for line in lines:
            try:
                text = _line_text(str(line.get("code")), dict(line.get("params") or {}))
            except (KeyError, IndexError, TypeError, ValueError):
                text = None
            if text:
                line["text"] = text
        out["sentences"] = [str(line["text"]) for line in lines]
    for warning in out.get("warnings") or []:
        text = _warning_text(warning)
        if text:
            warning["text"] = text
    return out
