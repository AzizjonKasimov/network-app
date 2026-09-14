package com.azizjon.network.ai

import org.json.JSONArray

/**
 * The tools the assistant may call, described for the model.
 *
 * Kept as literal JSON Schema because that is exactly what the gateway relays,
 * and a description is part of how the model decides what to call. The
 * descriptions carry the rules that belong to one tool; rules spanning several
 * live in [AssistantPrompt].
 */
object AssistantToolSchemas {
    /** Tools that write nothing. */
    val READ_TOOLS = setOf("list_people", "find_people", "get_person", "browse_records", "search_notes")

    /** Tools whose effect waits for the user to confirm it on a card. */
    val CONFIRMATION_TOOLS = setOf("delete_person", "delete_note", "delete_record", "merge_people")

    fun definitions(): JSONArray = JSONArray(JSON)

    private const val JSON = """
[
  {
    "name": "list_people",
    "description": "Lists saved people, one entry each: id, name, current work and study, location, relationship, tags, how many notes and records they have, and the date of their latest note. Use it to see who is in the network or to answer questions about the network as a whole. Archived people are left out unless include_archived is true. Long lists come in pages; pass next_offset to continue.",
    "input_schema": {
      "type": "object",
      "properties": {
        "include_archived": { "type": "boolean", "description": "Also list archived people." },
        "offset": { "type": "integer", "minimum": 0, "description": "next_offset from an earlier page." }
      }
    }
  },
  {
    "name": "find_people",
    "description": "Finds saved people by name, or by words in their profile, positions, education, needs, capabilities, background, and notes. Returns the closest matches with the text that matched. Use it before creating anyone, to check whether they are already saved. It matches words, not meaning, so use browse_records to judge who could help with something.",
    "input_schema": {
      "type": "object",
      "required": ["query"],
      "properties": {
        "query": { "type": "string", "minLength": 1, "maxLength": 200, "description": "A name, or words to look for." },
        "include_archived": { "type": "boolean", "description": "Also match archived people by name." }
      }
    }
  },
  {
    "name": "get_person",
    "description": "Reads everything saved about one person except their contact value: profile, positions, education, needs, capabilities, background facts, and their latest notes. Each record comes with a reference such as need:12 for the tools that update, convert, or delete records.",
    "input_schema": {
      "type": "object",
      "required": ["person_id"],
      "properties": {
        "person_id": { "type": "integer" },
        "notes_limit": { "type": "integer", "minimum": 1, "maximum": 100, "description": "How many of the latest notes to include. Default 20." }
      }
    }
  },
  {
    "name": "browse_records",
    "description": "Lists records across the whole network, newest first, each with the person it belongs to. Use it for questions such as who could help with something, who needs something, or who works in a field: reading the records lets you judge meaning instead of matching words. Closed needs, inactive capabilities, past positions, and archived people are left out unless include_inactive is true. Long lists come in pages; pass next_offset to continue.",
    "input_schema": {
      "type": "object",
      "properties": {
        "kinds": {
          "type": "array",
          "items": { "type": "string", "enum": ["position", "education", "need", "capability", "fact"] },
          "description": "Which kinds to include. Default all."
        },
        "include_inactive": { "type": "boolean" },
        "offset": { "type": "integer", "minimum": 0, "description": "next_offset from an earlier page." }
      }
    }
  },
  {
    "name": "search_notes",
    "description": "Searches notes, the dated records of conversations and updates, newest first. Filter by words, by person, and by date range, in any combination; with no filters it lists the latest notes.",
    "input_schema": {
      "type": "object",
      "properties": {
        "query": { "type": "string", "maxLength": 200, "description": "Words to look for. Notes containing more of them come first." },
        "person_id": { "type": "integer" },
        "from": { "type": "string", "description": "Earliest date, YYYY-MM-DD." },
        "to": { "type": "string", "description": "Latest date, YYYY-MM-DD." },
        "offset": { "type": "integer", "minimum": 0, "description": "next_offset from an earlier page." }
      }
    }
  },
  {
    "name": "create_person",
    "description": "Saves a new person. Check with find_people first. Fails when someone with the same name is already saved, unless allow_same_name is true because the user said this is a different person.",
    "input_schema": {
      "type": "object",
      "required": ["name"],
      "properties": {
        "name": { "type": "string", "minLength": 1, "maxLength": 200 },
        "location": { "type": "string", "maxLength": 500 },
        "relationship": { "type": "string", "maxLength": 500, "description": "How the user knows them." },
        "tags": { "type": "string", "maxLength": 500, "description": "Comma-separated." },
        "profile_notes": { "type": "string", "maxLength": 4000 },
        "contact": { "type": "string", "maxLength": 500, "description": "Only a contact detail the user typed in this conversation." },
        "allow_same_name": { "type": "boolean" }
      }
    }
  },
  {
    "name": "update_person",
    "description": "Changes a person's profile. Only the fields you pass change; an empty string clears a field. archived true hides the person from lists and searches without deleting anything, and false brings them back. Work, study, needs, capabilities, and background are records, not profile fields: use add_record and update_record for those.",
    "input_schema": {
      "type": "object",
      "required": ["person_id"],
      "properties": {
        "person_id": { "type": "integer" },
        "name": { "type": "string", "maxLength": 200 },
        "location": { "type": "string", "maxLength": 500 },
        "relationship": { "type": "string", "maxLength": 500, "description": "How the user knows them." },
        "tags": { "type": "string", "maxLength": 500, "description": "Comma-separated. Replaces the existing tags." },
        "profile_notes": { "type": "string", "maxLength": 4000, "description": "Replaces the existing profile notes." },
        "contact": { "type": "string", "maxLength": 500, "description": "Only a contact detail the user typed in this conversation. You cannot read the stored one." },
        "archived": { "type": "boolean" }
      }
    }
  },
  {
    "name": "add_note",
    "description": "Saves a dated note on a person: what the user learned or discussed, in the user's words. Pass the records the note supports in records, and they are saved with it, linked to it, in the same call. A record that cannot be saved, usually because the person already has it, is listed under skipped with the reason while the rest are saved.",
    "input_schema": {
      "type": "object",
      "required": ["person_id", "text"],
      "properties": {
        "person_id": { "type": "integer" },
        "text": { "type": "string", "minLength": 1, "maxLength": 4000 },
        "date": { "type": "string", "description": "When it happened, YYYY-MM-DD. Default today." },
        "records": {
          "type": "array",
          "maxItems": 20,
          "description": "Records this note supports, saved with it.",
          "items": {
            "type": "object",
            "required": ["kind"],
            "properties": {
              "kind": { "type": "string", "enum": ["position", "education", "need", "capability", "fact"] },
              "text": { "type": "string", "maxLength": 1000, "description": "For need, capability, and fact: one self-contained sentence." },
              "organization": { "type": "string", "maxLength": 500, "description": "For position: the employer or business. For education: the institution, or just the place when none is named." },
              "role": { "type": "string", "maxLength": 500, "description": "For position: the job. For education: what was studied, or the qualification." },
              "current": { "type": "boolean", "description": "For position and education: false when they have left or finished. Default true." },
              "date": { "type": "string", "description": "When this was last confirmed, YYYY-MM-DD. Default the note's date." }
            }
          }
        }
      }
    }
  },
  {
    "name": "edit_note",
    "description": "Corrects the text or date of a saved note.",
    "input_schema": {
      "type": "object",
      "required": ["note_id"],
      "properties": {
        "note_id": { "type": "integer" },
        "text": { "type": "string", "minLength": 1, "maxLength": 4000 },
        "date": { "type": "string", "description": "YYYY-MM-DD." }
      }
    }
  },
  {
    "name": "add_record",
    "description": "Adds a record to a person when there is no note to save it with. position and education take organization and role (at least one); need, capability, and fact take text. Fails if the person already has the same record, in which case update that one instead.",
    "input_schema": {
      "type": "object",
      "required": ["person_id", "kind"],
      "properties": {
        "person_id": { "type": "integer" },
        "kind": { "type": "string", "enum": ["position", "education", "need", "capability", "fact"] },
        "text": { "type": "string", "maxLength": 1000, "description": "For need, capability, and fact: one self-contained sentence." },
        "organization": { "type": "string", "maxLength": 500, "description": "For position: the employer or business. For education: the institution, or just the place when none is named." },
        "role": { "type": "string", "maxLength": 500, "description": "For position: the job. For education: what was studied, or the qualification." },
        "current": { "type": "boolean", "description": "For position and education: false when they have left or finished. Default true." },
        "date": { "type": "string", "description": "When this was last confirmed, YYYY-MM-DD. Default today." },
        "source_note_id": { "type": "integer", "description": "The note on the same person this came from." }
      }
    }
  },
  {
    "name": "update_record",
    "description": "Changes a saved record. Only the fields you pass change. active false closes a need or marks a capability inactive, and true reopens it. current false marks a position or education as past.",
    "input_schema": {
      "type": "object",
      "required": ["record"],
      "properties": {
        "record": { "type": "string", "description": "A record reference such as need:12." },
        "text": { "type": "string", "maxLength": 1000, "description": "For need, capability, and fact." },
        "organization": { "type": "string", "maxLength": 500, "description": "For position and education." },
        "role": { "type": "string", "maxLength": 500, "description": "For position and education." },
        "current": { "type": "boolean", "description": "For position and education." },
        "active": { "type": "boolean", "description": "For need and capability." },
        "date": { "type": "string", "description": "When this was last confirmed, YYYY-MM-DD." }
      }
    }
  },
  {
    "name": "change_record_kind",
    "description": "Turns a saved record into another kind, for example a need that is really a capability, or a position that is really education or a background fact. Keeps its date and source note. Turning a record into position or education needs organization or role; turning a position or education into another kind takes text, or uses the position's own wording.",
    "input_schema": {
      "type": "object",
      "required": ["record", "new_kind"],
      "properties": {
        "record": { "type": "string", "description": "A record reference such as need:12." },
        "new_kind": { "type": "string", "enum": ["position", "education", "need", "capability", "fact"] },
        "text": { "type": "string", "maxLength": 1000 },
        "organization": { "type": "string", "maxLength": 500 },
        "role": { "type": "string", "maxLength": 500 }
      }
    }
  },
  {
    "name": "move_note",
    "description": "Moves a note, and every record that cites it as its source, to another person. Use it when something was saved on the wrong person; create the right person first if they are not saved. Profile fields are not moved.",
    "input_schema": {
      "type": "object",
      "required": ["note_id", "to_person_id"],
      "properties": {
        "note_id": { "type": "integer" },
        "to_person_id": { "type": "integer" }
      }
    }
  },
  {
    "name": "delete_person",
    "description": "Asks the user to confirm deleting a person together with all their notes and records. Nothing is deleted until they confirm on a card under your reply.",
    "input_schema": {
      "type": "object",
      "required": ["person_id"],
      "properties": { "person_id": { "type": "integer" } }
    }
  },
  {
    "name": "delete_note",
    "description": "Asks the user to confirm deleting a note. Records that came from it are kept. Nothing is deleted until they confirm on a card under your reply.",
    "input_schema": {
      "type": "object",
      "required": ["note_id"],
      "properties": { "note_id": { "type": "integer" } }
    }
  },
  {
    "name": "delete_record",
    "description": "Asks the user to confirm deleting a record. To mark a need closed or a position past, use update_record instead. Nothing is deleted until they confirm on a card under your reply.",
    "input_schema": {
      "type": "object",
      "required": ["record"],
      "properties": { "record": { "type": "string", "description": "A record reference such as need:12." } }
    }
  },
  {
    "name": "merge_people",
    "description": "Asks the user to confirm merging two saved entries for the same person: every note and record moves to keep_person_id, empty profile fields on that entry are filled from the other, and the other entry is deleted. Nothing happens until they confirm on a card under your reply.",
    "input_schema": {
      "type": "object",
      "required": ["keep_person_id", "merge_person_id"],
      "properties": {
        "keep_person_id": { "type": "integer" },
        "merge_person_id": { "type": "integer" }
      }
    }
  }
]
"""
}
