terraform {
 required_providers {
    vra = {source = "vmware/vra"}
 }
 required_version = ">= 0.13"
}

provider "vra" {
  url           = var.vra_url
  refresh_token = var.vra_refresh_token
  insecure      = true
}

data "vra_project" "this" {
  name = var.vra_project
}

data "vra_catalog_item" "this" {
  name = var.vra_catalog_item
}

resource "vra_deployment" "names" {
  count           = length(var.vm_names)
  name            = "CICD-${var.vm_names[count.index]}"
  owner           = var.owner_account
  catalog_item_id = data.vra_catalog_item.this.id
  project_id = data.vra_project.this.id
  inputs = {
    ComputeInfra   = jsonencode({ "cpu" = var.vm_resources.cpu, "image" = var.vm_resources.image, "memory" = var.vm_resources.memory, "disksize" = var.vm_resources.disk_size, "disk_count" = var.vm_resources.disk_count })
  }
}